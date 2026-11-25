package com.kuma.motointercom.group.cloud

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal class GroupJoinReceipt(val id: String, val secret: String) {
    companion object {
        fun create(): GroupJoinReceipt {
            val secret = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "")
            val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII))
            return GroupJoinReceipt("j1-" + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }, secret)
        }
    }
}

internal class GroupJoinRetraction(private val receipt: GroupJoinReceipt, private val admission: CloudAdmittedSession?) {
    fun execute(api: GroupCloudApi) {
        api.cancelJoinRequest(receipt.id, receipt.secret)
        // Older servers can directly admit a member without indexing the client-supplied receipt.
        if (admission != null) {
            try {
                api.leaveRoom(admission.sessionToken, admission.roomId)
            } catch (error: GroupCloudException) {
                if (error.code !in setOf("UNAUTHORIZED", "ROOM_ENDED", "REMOVED", "RECOVERY_EXPIRED")) throw error
            }
        }
    }
}

internal class GroupJoinCancellation(val initial: GroupJoinReceipt = GroupJoinReceipt.create()) {
    var requested = false
    private var receipt = initial
    private var admission: CloudAdmittedSession? = null
    private var submitting = true
    private var inFlight = false
    private var retryAtMs = 0L

    fun submitted(result: CloudJoinSubmission?) {
        submitting = false
        when (result) {
            is CloudJoinSubmission.Pending -> receipt = GroupJoinReceipt(result.requestId, result.requestSecret)
            is CloudJoinSubmission.Admitted -> admission = result.session
            null -> Unit
        }
    }

    fun next(now: Long): GroupJoinRetraction? {
        if (!requested || submitting || inFlight || now < retryAtMs) return null
        inFlight = true
        return GroupJoinRetraction(receipt, admission)
    }

    fun retryAt(deadline: Long) {
        inFlight = false
        retryAtMs = deadline
    }
}
