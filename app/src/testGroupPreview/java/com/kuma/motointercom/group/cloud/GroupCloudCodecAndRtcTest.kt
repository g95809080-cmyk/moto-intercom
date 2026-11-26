package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupCloudCodecAndRtcTest {
    @Test
    fun urlPolicyRequiresHttpsOutsideLoopback() {
        assertEquals("https://cloud.motocom.example", GroupCloudUrlPolicy.normalizeAndValidate("https://cloud.motocom.example/"))
        assertEquals("http://127.0.0.1:8443", GroupCloudUrlPolicy.normalizeAndValidate("http://127.0.0.1:8443/"))
        assertEquals("http://localhost:8443", GroupCloudUrlPolicy.normalizeAndValidate("http://localhost:8443"))
        assertEquals("http://10.0.2.2:8443", GroupCloudUrlPolicy.normalizeAndValidate("http://10.0.2.2:8443"))
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeAndValidate("http://192.168.1.20:8443")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeAndValidate("https://user:pass@cloud.motocom.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeAndValidate("https://cloud.motocom.example?token=secret")
        }
    }

    @Test
    fun urlPolicyEnforcesProductionHostAllowlistWithoutLoopback() {
        val allowedHosts = setOf("group.toneride.top")
        assertEquals(
            "https://group.toneride.top",
            GroupCloudUrlPolicy.normalizeAndValidate(
                "https://group.toneride.top/",
                allowedHosts = allowedHosts,
                allowLoopback = false
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeAndValidate(
                "https://evil.example",
                allowedHosts = allowedHosts,
                allowLoopback = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeAndValidate(
                "http://127.0.0.1:8443",
                allowedHosts = allowedHosts,
                allowLoopback = false
            )
        }
    }

    @Test
    fun inviteCodeNormalizesAndRedactsToString() {
        val code = GroupCloudInviteCode("012-345")
        assertEquals("012345", code.value)
        assertFalse(code.toString().contains("012345"))
        assertTrue(GroupCloudInviteCode.isValid(" 987 654 "))
        assertFalse(GroupCloudInviteCode.isValid("AB23CD45"))
        assertFalse(GroupCloudInviteCode.isValid("12345A"))
        assertFalse(GroupCloudInviteCode.isValid("1234567"))
        assertThrows(IllegalArgumentException::class.java) { GroupCloudInviteCode("12345") }
    }

    @Test
    fun rtcTokenEnforcesValidityRefreshMarginAndRedactsSecrets() {
        val now = 1_750_000_000_000L
        val token = CloudRtcToken(
            appId = "volc-app-id",
            rtcRoomId = "rtc-room-12345678",
            rtcUserId = "rtc-user-87654321",
            rtcToken = "secret-formal-v001-token",
            expiresAtMs = now + 60_000L
        )
        assertTrue(token.isUsable(now))
        assertFalse(token.needsRefresh(now))
        assertFalse(token.toString().contains(token.rtcToken))

        assertTrue(token.copy(expiresAtMs = now + 30_000L).needsRefresh(now))
        assertTrue(token.copy(expiresAtMs = now + 4_999L).let { !it.isUsable(now) })
        assertThrows(IllegalArgumentException::class.java) {
            token.copy(rtcToken = "")
        }
    }

    @Test
    fun rtcCredentialPayloadParsesWithoutExposingSecretsInToString() {
        val session = GroupCloudCodec.parseAdmittedSession(JSONObject().apply {
            put("roomId", "room-1")
            put("sessionToken", "session-secret")
            put("tokenExpiresAtMs", 1_750_003_600_000L)
            put("latestSeq", 7)
            put("snapshot", snapshotJson(memberCount = 1))
            put("rtc", JSONObject().apply {
                put("appId", "volc-app-id")
                put("rtcRoomId", "rtc-room")
                put("rtcUserId", "rtc-user")
                put("rtcToken", "formal-token-secret")
                put("expiresAtMs", 1_750_000_600_000L)
            })
        })
        val rtc = checkNotNull(session.rtc)
        assertEquals("volc-app-id", rtc.appId)
        assertEquals("rtc-room", rtc.rtcRoomId)
        assertEquals(50, session.snapshot.view.maxMembers)
        assertEquals(7L, session.latestSeq)
        assertFalse(session.toString().contains("formal-token-secret"))
        assertFalse(rtc.toString().contains("formal-token-secret"))
    }

    @Test
    fun cloudRoomViewDecouplesOwnerFromHostAndSupportsFiftyVisibleMembers() {
        val parsed = GroupCloudCodec.parseSnapshot(snapshotJson(memberCount = 50))
        assertEquals(GroupMode.NETWORK, parsed.view.mode)
        assertEquals("dev-1", parsed.view.ownerId)
        assertNull(parsed.view.hostDeviceId)
        assertEquals(50, parsed.view.members.size)
        assertEquals(50, parsed.view.maxMembers)
        assertTrue(parsed.view.links.isEmpty())
        assertFalse(parsed.view.canEnterRider(GroupRole.OWNER))

        val tooMany = snapshotJson(memberCount = 51)
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudCodec.parseSnapshot(tooMany)
        }
    }

    @Test
    fun dualOwnerSnapshotFailsClosed() {
        val json = snapshotJson(memberCount = 2)
        json.getJSONArray("members").put(JSONObject().apply {
            put("deviceId", "dev-2")
            put("runtimeId", "run-2")
            put("nickname", "伪群主")
            put("role", "OWNER")
            put("status", "ADMITTED")
            put("incarnation", 2)
            put("controlGeneration", 1)
        })
        assertThrows(IllegalArgumentException::class.java) { GroupCloudCodec.parseSnapshot(json) }
    }

    private fun snapshotJson(memberCount: Int): JSONObject = JSONObject().apply {
        put("roomId", "room-1")
        put("roomEpoch", "epoch-1")
        put("mode", "NETWORK")
        put("channel", "PUBLIC")
        put("revision", 3)
        put("ownerId", "dev-1")
        put("ended", false)
        put("requireApproval", true)
        put("maxMembers", 50)
        put("members", JSONArray().apply {
            for (index in 1..memberCount) {
                put(JSONObject().apply {
                    put("deviceId", "dev-$index")
                    put("runtimeId", "run-$index")
                    put("nickname", if (index == 1) "队长" else "骑士 $index")
                    put("role", if (index == 1) "OWNER" else "MEMBER")
                    put("status", "ADMITTED")
                    put("incarnation", 1)
                    put("controlGeneration", 1)
                    put("audioAvailable", true)
                })
            }
        })
        put("pendingRequests", JSONArray())
        put("removed", JSONArray())
    }
}
