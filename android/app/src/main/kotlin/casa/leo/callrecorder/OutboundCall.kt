package casa.leo.callrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.DefaultAudioDevice
import com.twilio.voice.LogLevel
import com.twilio.voice.LogModule
import com.twilio.voice.Voice
import kotlinx.coroutines.*

/**
 * Direct outbound Twilio call — no Telecom, no ConnectionService.
 *
 * Why we bypass Telecom for outbound:
 *  - The previous Telecom-routed flow placed the call through the system
 *    in-call UI. Our Activity backgrounded, our `HermesConnectionService`
 *    held the Connection, but on Android 12+ a non-foreground service
 *    cannot reliably capture the mic — AudioRecord opens but produces
 *    zeros. That's why every Twilio recording came back with channel 0
 *    silent (RMS ~0.5) while channel 1 (the called party) was crystal
 *    clear (RMS ~3500).
 *  - Working VoIP apps (Zoom, WhatsApp, Twilio's own sample) keep the
 *    call inside their own Activity + a `microphone|phoneCall`
 *    foreground service so the mic is guaranteed alive.
 *
 * Lifecycle: caller invokes `start()`, which:
 *   1. Starts `CallForegroundService` (microphone + phoneCall types)
 *   2. Sets `MODE_IN_COMMUNICATION` and grabs voice-call audio focus
 *   3. Fetches a Twilio token and calls `Voice.connect()`
 *   4. Routes Twilio call lifecycle events through the supplied listener
 *
 * On hangup / failure / disconnect, `stop()` undoes all of the above.
 */
class OutboundCall(
    private val ctx: Context,
    private val toNumber: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onDialing()
        fun onRinging()
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onError(reason: String)
    }

    companion object { private const val TAG = "OutboundCall" }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var twilioCall: Call? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerOn = false
    private var focusRequest: AudioFocusRequest? = null
    private var stopped = false

    fun start() {
        listener.onDialing()
        // Foreground service FIRST — guarantees mic access even if the screen
        // locks during the call. On Android 14+ this is non-negotiable: without
        // foregroundServiceType=microphone, AudioRecord returns silence.
        CallForegroundService.start(ctx, toNumber)

        // Force the Twilio SDK to use software acoustic echo cancellation /
        // noise suppression instead of the hardware effects.
        //
        // On many Android devices (Pixel and Samsung among them) the
        // hardware AEC implementation is broken when used with WebRTC's
        // AudioRecord — capture comes back as pure zeros even though
        // permission is granted, the foreground service is up, and the
        // audio mode is MODE_IN_COMMUNICATION. Twilio's official troubleshooting
        // guide identifies this as the cause for "channel 0 silent" on
        // dual-channel server-side recordings, which is exactly what we hit
        // (the remote party captured perfectly on channel 1, the caller's mic on channel 0
        // RMS=0). DefaultAudioDevice with HW AEC disabled hands AEC to the
        // WebRTC software stack, which works on every device.
        try {
            val device = DefaultAudioDevice().apply {
                setUseHardwareAcousticEchoCanceler(false)
                setUseHardwareNoiseSuppressor(false)
            }
            Voice.setAudioDevice(device)
            Log.i(TAG, "Twilio audio device configured: HW AEC=false, HW NS=false")
        } catch (e: Exception) {
            Log.w(TAG, "setAudioDevice failed: ${e.message}")
        }

        // Verbose WebRTC logging while we hunt down silent-mic regressions.
        // Drop this once recordings show channel 0 with audio for a few
        // calls in a row.
        try {
            Voice.setLogLevel(LogLevel.DEBUG)
            Voice.setModuleLogLevel(LogModule.WEBRTC, LogLevel.DEBUG)
        } catch (e: Exception) {
            Log.w(TAG, "setLogLevel failed: ${e.message}")
        }

        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { Api.fetchToken() }

                // Acquire audio mode + focus on the main thread, IMMEDIATELY
                // before Voice.connect(). Closes any window for another
                // process to grab the audio session.
                acquireAudio()
                logPreflight()

                // Run the raw-mic diagnostic on a background thread.
                // Result POSTs to /api/diag/mic — answers the question
                // "is the mic actually delivering samples right now?"
                // independent of whatever Twilio is doing with WebRTC.
                withContext(Dispatchers.IO) {
                    MicDiag.run(ctx, extra = "pre_voice_connect to=$toNumber")
                }

                val options = ConnectOptions.Builder(token)
                    .params(mapOf("To" to toNumber))
                    .build()

                twilioCall = Voice.connect(ctx, options, object : Call.Listener {
                    override fun onConnectFailure(call: Call, error: CallException) {
                        Log.e(TAG, "Twilio connect failed: ${error.message}")
                        listener.onError(error.message ?: "connect failed")
                        stop()
                    }
                    override fun onRinging(call: Call) {
                        Log.i(TAG, "Ringing")
                        listener.onRinging()
                    }
                    override fun onConnected(call: Call) {
                        Log.i(TAG, "Connected sid=${call.sid} " +
                            "audioMode=${audioManager.mode}")
                        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            Log.w(TAG, "Audio mode drifted to ${audioManager.mode} — resetting")
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        }
                        listener.onConnected()
                    }
                    override fun onReconnecting(call: Call, e: CallException) {
                        Log.w(TAG, "Reconnecting: ${e.message}")
                    }
                    override fun onReconnected(call: Call) {
                        Log.i(TAG, "Reconnected")
                    }
                    override fun onDisconnected(call: Call, error: CallException?) {
                        Log.i(TAG, "Disconnected: ${error?.message ?: "ok"}")
                        listener.onDisconnected(error?.message)
                        stop()
                    }
                    override fun onCallQualityWarningsChanged(
                        call: Call,
                        currentWarnings: MutableSet<Call.CallQualityWarning>,
                        previousWarnings: MutableSet<Call.CallQualityWarning>,
                    ) {
                        Log.w(TAG, "Quality warnings: $currentWarnings")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "start() failed", e)
                listener.onError(e.message ?: "start failed")
                stop()
            }
        }
    }

    fun hangup() {
        twilioCall?.disconnect()
        // The disconnect callback above runs stop(). If for some reason it
        // doesn't fire (already-disconnected race), stop() defensively below.
        stop()
    }

    fun mute(muted: Boolean) {
        twilioCall?.mute(muted)
    }

    fun isMuted(): Boolean = twilioCall?.isMuted ?: false

    fun setSpeakerOn(on: Boolean) {
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        } catch (e: Exception) {
            Log.w(TAG, "setSpeakerOn: ${e.message}")
        }
    }

    /** Log everything we can sample about the audio + mic state at the
     *  moment Voice.connect() runs. If the recording comes back silent
     *  again, this single line tells us whether perms, audio mode, or
     *  battery-optimization were the cause. */
    private fun logPreflight() {
        val micGranted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val pm = ctx.getSystemService(PowerManager::class.java)
        val battOptIgnored = try {
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        } catch (_: Exception) { false }
        Log.i(
            TAG,
            "PREFLIGHT | audioMode=${audioManager.mode} (want ${AudioManager.MODE_IN_COMMUNICATION}) | " +
                "RECORD_AUDIO=$micGranted | " +
                "battOptIgnored=$battOptIgnored | " +
                "speakerOn=${audioManager.isSpeakerphoneOn} | " +
                "micMute=${audioManager.isMicrophoneMute}"
        )
        if (audioManager.isMicrophoneMute) {
            // Belt-and-suspenders: if some other app left the mic muted at the
            // system level, unmute it. (This is rare but has been observed.)
            try {
                @Suppress("DEPRECATION")
                audioManager.isMicrophoneMute = false
                Log.w(TAG, "System mic was muted — unmuted defensively")
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear system mic mute: ${e.message}")
            }
        }
    }

    private fun acquireAudio() {
        savedAudioMode = audioManager.mode
        savedSpeakerOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* no-op */ }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun releaseAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioManager.mode = savedAudioMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = savedSpeakerOn
        } catch (e: Exception) {
            Log.w(TAG, "releaseAudio: ${e.message}")
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try { twilioCall?.disconnect() } catch (_: Exception) {}
        twilioCall = null
        releaseAudio()
        CallForegroundService.stop(ctx)
        scope.cancel()
        Log.i(TAG, "Stopped — audio released, foreground service stopped")
    }
}
