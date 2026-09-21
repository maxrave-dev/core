package com.maxrave.data.loginsync

import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncException
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.LoginSyncInvite
import org.simpmusic.loginsync.LoginSyncTransportException

/** Android: turns this device's sign-ins into a payload and hands it to the loginSync service. */
internal class LoginSyncSenderRepositoryImpl(
    private val store: LoginSyncStore,
    private val client: LoginSyncClient,
) : LoginSyncSenderRepository {
    override suspend fun signedInServices(): List<LoginSyncService> = store.signedIn()

    override fun isInvite(qrText: String): Boolean = LoginSyncInvite.parse(qrText) != null

    override suspend fun connect(qrText: String): DesktopInfo {
        val invite = LoginSyncInvite.parse(qrText) ?: throw LoginSyncException(LoginSyncException.Reason.FAILED, "not a login code")
        val device = transport { client.connect(invite) }
        return DesktopInfo(device.name, device.os)
    }

    override suspend fun send(services: Set<LoginSyncService>): List<LoginSyncService> {
        val payload = loginSyncJson.encodeToString(LoginBundle.serializer(), store.bundle(services)).encodeToByteArray()
        val reply = transport { client.send(payload) }
        val ack = loginSyncJson.decodeFromString(LoginSyncAck.serializer(), reply.decodeToString())
        return ack.services.mapNotNull { name -> LoginSyncService.entries.firstOrNull { it.name == name } }
    }

    override fun disconnect() = client.disconnect()

    private inline fun <T> transport(block: () -> T): T =
        try {
            block()
        } catch (e: LoginSyncTransportException) {
            val reason =
                when (e.kind) {
                    LoginSyncTransportException.Kind.UNREACHABLE -> LoginSyncException.Reason.UNREACHABLE
                    LoginSyncTransportException.Kind.REFUSED -> LoginSyncException.Reason.CODE_EXPIRED
                    LoginSyncTransportException.Kind.FAILED -> LoginSyncException.Reason.FAILED
                }
            throw LoginSyncException(reason, e.detail)
        }
}
