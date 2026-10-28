package dev.latintzar.hermescaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service kept alive during an outbound call so the OS guarantees
 * mic access even if the screen locks or the user navigates away from the
 * Activity. On Android 14+ the `microphone|phoneCall` foregroundServiceType
 * is REQUIRED for any background mic capture — without it, AudioRecord
 * silently produces zeros, which is exactly what we were seeing on the
 * Twilio recordings (channel 0 = silent the caller).
 *
 * Started by `OutboundCall` immediately before `Voice.connect()`,
 * stopped when the call disconnects.
 *
 * Lifecycle is independent of MainActivity. It must NOT do any audio work
 * itself (that lives in OutboundCall on the activity side); it exists only
 * to hold the foreground state.
 */
class CallForegroundService : Service() {

    companion object {
        private const val TAG = "CallFgSvc"
        private const val CHANNEL_ID = "hermes_call"
        private const val NOTIF_ID = 4711
        const val EXTRA_NUMBER = "number"

        fun start(ctx: Context, number: String) {
            val intent = Intent(ctx, CallForegroundService::class.java)
                .putExtra(EXTRA_NUMBER, number)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CallForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: ""
        ensureChannel()

        val tapBack = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Hermes Caller — call active")
            .setContentText(if (number.isNotBlank()) "→ $number" else "Recording in progress")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(tapBack)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: must declare the types at startForeground() time.
                // Both microphone AND phoneCall — mic for capture, phoneCall to
                // claim VoIP semantics so the system call manager treats us right.
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            Log.i(TAG, "Foreground started — number=$number, sdk=${Build.VERSION.SDK_INT}")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }

        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Active call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a Hermes Caller call is in progress"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        Log.i(TAG, "Foreground service stopping")
        super.onDestroy()
    }
}
