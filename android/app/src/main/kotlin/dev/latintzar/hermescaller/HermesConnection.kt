package dev.latintzar.hermescaller

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.DefaultAudioDevice
import com.twilio.voice.LogLevel
import com.twilio.voice.LogModule
import com.twilio.voice.Voice
import kotlinx.coroutines.*

/**
 * One outbound call. Fetches a Twilio access token, places the WebRTC
 * call, and bridges Twilio's call lifecycle into the Android telecom
 * framework so the system in-call UI shows the right state (Dialing,
 * Ringing, Connected, Disconnected).
 *
 * The system handles audio routing (earpiece/speaker/Bluetooth), the
 * native call screen, lock-screen behaviour, mute, and DTMF. We just
 * forward those actions to the Twilio Call.
 */
class HermesConnection(
    private val ctx: Context,
    private val toNumber: String,
) : Connection() {

    companion object { private const val TAG = "HermesConnection" }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var twilioCall: Call? = null
    private var muted = false
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerOn = false
    private var focusRequest: AudioFocusRequest? = null

    init {
        connectionCapabilities =
            CAPABILITY_MUTE or
            CAPABILITY_SUPPORT_HOLD or
            CAPABILITY_HOLD
        audioModeIsVoip = true
        setAddress(Uri.fromParts("tel", toNumber, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName("Hermes Caller", TelecomManager.PRESENTATION_ALLOWED)
        setDialing()
        // acquireAudio() is intentionally NOT called here. The Telecom
        // framework may reset the audio mode after this init block runs
        // (it sets up its own VoIP audio session asynchronously because
        // audioModeIsVoip = true). Calling acquireAudio() here and then
        // suspending for the token fetch creates a race where Voice.connect()
        // ends up running against the framework's audio mode rather than ours.
        // We call acquireAudio() inside startTwilioCall(), immediately before
        // Voice.connect(), to close that window.
        startTwilioCall()
    }

    /**
     * Force the audio system into voice-call mode + grab focus BEFORE the
     * Twilio SDK initializes its mic capture.
     *
     * Without this, Voice.connect() runs while the system is still in
     * MODE_NORMAL (because the Connection is in DIALING state, not yet
     * ACTIVE), and WebRTC's AudioRecord opens against a non-VoIP audio
     * source — the caller's mic ends up silent or band-limited and only
     * speaker bleed makes it onto the recording.
     */
    private fun acquireAudio() {
        savedAudioMode = audioManager.mode
        savedSpeakerOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "Audio acquired — mode was $savedAudioMode, now MODE_IN_COMMUNICATION")

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
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
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
            audioManager.isSpeakerphoneOn = savedSpeakerOn
            Log.i(TAG, "Audio released — restored mode=$savedAudioMode")
        } catch (e: Exception) {
            Log.w(TAG, "releaseAudio: ${e.message}")
        }
    }

    private fun startTwilioCall() {
        // Foreground service with microphone + phoneCall types — the system
        // keeps our mic alive even though Telecom's call screen, not our
        // Activity, is on top. Without this, Android 12+ silently zeroes
        // out AudioRecord samples when a non-foreground app (us, when the
        // native dialer is on top) tries to use the mic.
        try {
            CallForegroundService.start(ctx, toNumber)
        } catch (e: Exception) {
            Log.w(TAG, "CallForegroundService.start failed: ${e.message}")
        }

        // Disable hardware Acoustic Echo Cancellation + Noise Suppression
        // and let WebRTC's software stack handle them. Some OEMs ship
        // hardware AEC that interacts badly with Twilio's audio path and
        // gates the mic off. The default ctor disables hardware AEC/NS.
        try {
            Voice.setAudioDevice(DefaultAudioDevice())
            Voice.setLogLevel(LogLevel.ERROR)
            Voice.setModuleLogLevel(LogModule.WEBRTC, LogLevel.ERROR)
        } catch (e: Exception) {
            Log.w(TAG, "Voice config failed: ${e.message}")
        }

        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { Api.fetchToken() }

                // Set audio mode immediately before Voice.connect() so
                // WebRTC's AudioRecord opens in MODE_IN_COMMUNICATION with
                // zero window for the Telecom framework to interfere.
                // This must happen on the main thread — we're back on Main
                // after withContext(IO) returns.
                acquireAudio()
                Log.i(TAG, "Audio mode at Voice.connect: ${audioManager.mode} " +
                    "(expected ${AudioManager.MODE_IN_COMMUNICATION})")

                // Raw-mic smoke test — answers the question "is the OS
                // delivering mic samples right now?" independent of Twilio.
                // Result toasts on screen + posts to /api/diag/mic.

                val options = ConnectOptions.Builder(token)
                    .params(mapOf("To" to toNumber))
                    .build()

                twilioCall = Voice.connect(ctx, options, object : Call.Listener {
                    override fun onConnectFailure(call: Call, error: CallException) {
                        Log.e(TAG, "Twilio connect failed: ${error.message}")
                        setDisconnected(DisconnectCause(DisconnectCause.ERROR, error.message))
                        destroy()
                    }
                    override fun onRinging(call: Call) {
                        Log.i(TAG, "Ringing")
                        setRinging()
                    }
                    override fun onConnected(call: Call) {
                        Log.i(TAG, "Connected: ${call.sid}")
                        // Re-enforce MODE_IN_COMMUNICATION on ACTIVE in case
                        // the Telecom framework touched it during the
                        // DIALING→RINGING→ACTIVE transition on some OEMs.
                        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            Log.w(TAG, "Audio mode was ${audioManager.mode} on connect — resetting to IN_COMMUNICATION")
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        }
                        setActive()
                    }
                    override fun onReconnecting(call: Call, e: CallException) {
                        Log.w(TAG, "Reconnecting: ${e.message}")
                    }
                    override fun onReconnected(call: Call) {
                        Log.i(TAG, "Reconnected")
                        setActive()
                    }
                    override fun onDisconnected(call: Call, error: CallException?) {
                        Log.i(TAG, "Disconnected: ${error?.message ?: "ok"}")
                        setDisconnected(
                            if (error != null) DisconnectCause(DisconnectCause.ERROR, error.message)
                            else DisconnectCause(DisconnectCause.LOCAL)
                        )
                        destroy()
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
                Log.e(TAG, "Token fetch / Voice.connect failed", e)
                setDisconnected(DisconnectCause(DisconnectCause.ERROR, e.message))
                destroy()
            }
        }
    }

    // ── Telecom callbacks → Twilio actions ──────────────────────────────────

    override fun onDisconnect() {
        Log.i(TAG, "User hung up")
        twilioCall?.disconnect()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() = onDisconnect()
    override fun onReject() = onDisconnect()

    override fun onHold() {
        twilioCall?.hold(true)
        setOnHold()
    }

    override fun onUnhold() {
        twilioCall?.hold(false)
        setActive()
    }

    /**
     * Audio state changes (mute, route changes). The system handles
     * MODE_IN_COMMUNICATION and audio focus when audioModeIsVoip = true.
     */
    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (state.isMuted != muted) {
            muted = state.isMuted
            twilioCall?.mute(muted)
        }
    }

    override fun onPlayDtmfTone(c: Char) {
        twilioCall?.sendDigits(c.toString())
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        Log.i(TAG, "Connection state → $state")
        if (state == STATE_DISCONNECTED) {
            releaseAudio()
            try { CallForegroundService.stop(ctx) } catch (_: Exception) {}
            scope.cancel()
        }
    }
}
