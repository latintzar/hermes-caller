package dev.latintzar.hermescaller

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

/**
 * The Android telecom framework binds to this service whenever the user
 * picks "Hermes Caller" as the calling account for a tel: URI — whether
 * that came from Google Maps, contacts, a browser tel: link, or our own
 * dial pad.
 *
 * For each outbound dial we create a HermesConnection that bridges the
 * Twilio Voice call into the system Connection lifecycle. The system
 * shows the native green-circle call UI; we just keep state in sync.
 */
class HermesConnectionService : ConnectionService() {

    companion object { private const val TAG = "HermesConnSvc" }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val to = request?.address?.schemeSpecificPart
            ?: request?.address?.toString()
            ?: ""
        Log.i(TAG, "onCreateOutgoingConnection to=$to")
        return HermesConnection(applicationContext, to)
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        Log.w(TAG, "onCreateOutgoingConnectionFailed: ${request?.address}")
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        // We don't accept incoming calls — Twilio inbound is not wired up.
        Log.w(TAG, "onCreateIncomingConnection (rejected — outbound-only app)")
        return Connection.createCanceledConnection()
    }
}
