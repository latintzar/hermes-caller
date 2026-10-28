package dev.latintzar.hermescaller

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Registers Hermes Caller as a system-level calling provider.
 *
 * Once the user enables this account in Settings → Calling accounts,
 * Android shows the "Call with…" chooser whenever a tel: link is tapped
 * (Google Maps, contacts, browser, our own dial pad). Picking Hermes
 * Caller routes the call through `HermesConnectionService`, which then
 * places it via the Twilio Voice SDK with [configured caller ID] as caller ID.
 */
object Telecom {
    private const val TAG = "HermesTelecom"
    private const val ACCOUNT_ID = "hermes-caller"
    private const val ACCOUNT_LABEL = "Hermes Caller"

    fun phoneAccountHandle(ctx: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(ctx, HermesConnectionService::class.java),
            ACCOUNT_ID
        )

    fun registerAccount(ctx: Context) {
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = phoneAccountHandle(ctx)

            val account = PhoneAccount.builder(handle, ACCOUNT_LABEL)
                .setShortDescription("VoIP dialer for the caller (Twilio + Hermes)")
                .setCapabilities(
                    PhoneAccount.CAPABILITY_CALL_PROVIDER or
                    PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING.inv().and(0)  // no-op; keep audio-only
                )
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()

            tm.registerPhoneAccount(account)
            Log.i(TAG, "PhoneAccount registered: $handle")
        } catch (e: Exception) {
            Log.e(TAG, "registerAccount failed", e)
        }
    }

    /**
     * True when our PhoneAccount has been enabled in Settings →
     * Calling accounts. We can't enable it ourselves — Android requires
     * an explicit user toggle for security.
     */
    fun isAccountEnabled(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Fall back: rely on TelecomManager.getCallCapablePhoneAccounts which doesn't
            // need READ_PHONE_STATE on most devices — we ask permission anyway so the
            // public list method works.
        }
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = phoneAccountHandle(ctx)
            // getCallCapablePhoneAccounts() returns only enabled accounts.
            tm.callCapablePhoneAccounts.any { it == handle }
        } catch (e: SecurityException) {
            Log.w(TAG, "isAccountEnabled: SecurityException, assuming not enabled")
            false
        } catch (e: Exception) {
            Log.w(TAG, "isAccountEnabled failed: ${e.message}")
            false
        }
    }

    /** Open Settings → Calling accounts so the user can flip the switch. */
    fun openCallingAccountsSettings(ctx: Context) {
        try {
            val intent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open calling accounts settings; trying generic", e)
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* nothing */ }
        }
    }

    /**
     * Place an outbound call through our PhoneAccount. The system shows
     * the native in-call UI (no need for our own CallActivity). If the
     * account isn't enabled, returns false so MainActivity can prompt.
     */
    fun placeCall(ctx: Context, e164Number: String): Boolean {
        if (!isAccountEnabled(ctx)) {
            Log.w(TAG, "PhoneAccount not enabled — user needs to enable in Settings")
            return false
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CALL_PHONE permission not granted")
            return false
        }

        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", e164Number, null)
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                phoneAccountHandle(ctx)
            )
        }
        return try {
            tm.placeCall(uri, extras)
            Log.i(TAG, "placeCall($e164Number) dispatched to ConnectionService")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "placeCall denied by system", e)
            false
        }
    }
}
