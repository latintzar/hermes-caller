package casa.leo.callrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Microphone smoke-test that runs RIGHT BEFORE Voice.connect(), captures
 * 2 seconds of raw audio on `VOICE_COMMUNICATION`, computes RMS, and posts
 * the result to the server. Lets us tell from the server logs alone whether:
 *
 *   - rms ~ 0   → mic is dead at the OS level. Twilio Voice SDK is not the
 *                  problem — focus on permissions / OEM mic lockout / hardware.
 *   - rms > ~50 → mic works fine. Twilio Voice SDK is failing to route it
 *                  into the WebRTC stream. Focus on AudioSwitch /
 *                  setAudioDevice / SDK version.
 *
 * The whole probe takes 2s and runs once per call attempt.
 */
object MicDiag {

    private const val TAG = "MicDiag"
    private const val SAMPLE_RATE = 16000
    private const val DURATION_MS = 2000

    /** Run synchronously — call from a background thread, NOT main. */
    fun run(ctx: Context, extra: String = "") {
        val payload = org.json.JSONObject()
        payload.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        payload.put("sdk", Build.VERSION.SDK_INT)
        payload.put("extra", extra)

        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        payload.put("record_perm", granted)

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        payload.put("audio_mode", am.mode)
        payload.put("mic_mute", am.isMicrophoneMute)

        val pm = ctx.getSystemService(PowerManager::class.java)
        payload.put("batt_opt_ignored",
            try { pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false }
            catch (_: Exception) { false })

        if (!granted) {
            payload.put("rms", -1)
            payload.put("max", -1)
            payload.put("note", "RECORD_AUDIO not granted")
            Api.postMicDiag(payload)
            return
        }

        try {
            val (rms, max, note) = sampleMic()
            payload.put("rms", rms)
            payload.put("max", max)
            if (note.isNotBlank()) payload.put("note", note)
            Log.i(TAG, "Probe done: rms=$rms max=$max audioMode=${am.mode}")
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            payload.put("rms", -2)
            payload.put("max", -2)
            payload.put("note", "exception: ${e.message}")
        }

        Api.postMicDiag(payload)

        // Surface result on-screen so we can see it without server logs.
        // The mic test is the single most informative signal we have when
        // recordings come back silent — show it big.
        val rms = payload.optInt("rms", -99)
        val max = payload.optInt("max", -99)
        val verdict = when {
            rms < 0 -> "MIC ERROR rms=$rms"
            rms < 50 -> "MIC SILENT rms=$rms (OS-level problem)"
            else -> "MIC OK rms=$rms max=$max"
        }
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, verdict, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "toast failed: ${e.message}")
            }
        }
    }

    private data class Result(val rms: Int, val max: Int, val note: String)

    private fun sampleMic(): Result {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return Result(-3, -3, "minBuf<=0 ($minBuf)")

        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)  // ~1s buffer
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return Result(-4, -4, "AudioRecord init failed (state=${rec.state})")
        }

        val totalShorts = SAMPLE_RATE * DURATION_MS / 1000
        val chunk = ShortArray(2048)
        var sumSq = 0.0
        var count = 0L
        var maxAbs = 0
        try {
            rec.startRecording()
            var read = 0
            while (read < totalShorts) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    val a = if (v < 0) -v else v
                    if (a > maxAbs) maxAbs = a
                    sumSq += (v.toDouble() * v.toDouble())
                    count++
                }
                read += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }

        val rms = if (count > 0) sqrt(sumSq / count).toInt() else 0
        return Result(rms, maxAbs, "")
    }
}
