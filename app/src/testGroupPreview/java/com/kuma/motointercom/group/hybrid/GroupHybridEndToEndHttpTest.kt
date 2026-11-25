package com.kuma.motointercom.group.hybrid

import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/** End-to-end wire test: Android HttpGroupCloudClient HYBRID APIs against live Node tools/group-cloud/server.mjs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupHybridEndToEndHttpTest {
    private fun findServerScript(): File {
        val candidates = listOf(
            File("tools/group-cloud/server.mjs"),
            File("../tools/group-cloud/server.mjs")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate tools/group-cloud/server.mjs from ${File(".").absolutePath}")
    }

    private data class RecordedOpenApiCall(val action: String, val authorization: String, val body: JSONObject)

    private fun startMockOpenApiServer(): Pair<HttpServer, MutableList<RecordedOpenApiCall>> {
        val calls = Collections.synchronizedList(mutableListOf<RecordedOpenApiCall>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val action = Regex("Action=([^&]+)").find(query)?.groupValues?.get(1).orEmpty()
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
            calls += RecordedOpenApiCall(action, auth, JSONObject(bodyText.ifBlank { "{}" }))
            val responseBytes = """{"ResponseMetadata":{"Action":"$action"},"Result":{"Message":"success"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()
        return server to calls
    }

    private fun startNodeServer(openApiEndpoint: String): Pair<Process, String> {
        val port = ServerSocket(0).use { it.localPort }
        val script = findServerScript().canonicalFile
        val process = ProcessBuilder("node", script.absolutePath)
            .apply {
                environment()["GROUP_CLOUD_HOST"] = "127.0.0.1"
                environment()["GROUP_CLOUD_PORT"] = port.toString()
                environment()["GROUP_CLOUD_MAX_MEMBERS"] = "50"
                environment()["GROUP_CLOUD_VOLC_APP_ID"] = "e2e-hybrid-app"
                environment()["GROUP_CLOUD_VOLC_APP_KEY"] = "e2e-hybrid-app-key"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_AK"] = "e2e-hybrid-ak"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_SK"] = "e2e-hybrid-sk"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_ENDPOINT"] = openApiEndpoint
                redirectErrorStream(true)
            }
            .start()
        val output = StringBuilder()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
            }
        }.apply { isDaemon = true }.start()
        val baseUrl = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                if (conn.responseCode == 200) {
                    conn.disconnect()
                    return process to baseUrl
                }
                conn.disconnect()
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        process.destroyForcibly()
        error("Node server failed to start: $output")
    }

    @Test
    fun hybridEndToEndHttpSupportsFourSeatCapRolesChannelsPrivateAndTransportSwitch() {
        // Verify server.mjs fails closed at startup when OpenAPI AK/SK are missing.
        val missingOpenApi = ProcessBuilder("node", findServerScript().canonicalFile.absolutePath)
            .apply {
                environment()["GROUP_CLOUD_HOST"] = "127.0.0.1"
                environment()["GROUP_CLOUD_PORT"] = "0"
                environment()["GROUP_CLOUD_VOLC_APP_ID"] = "e2e-hybrid-app"
                environment()["GROUP_CLOUD_VOLC_APP_KEY"] = "e2e-hybrid-app-key"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_AK"] = ""
                environment()["GROUP_CLOUD_VOLC_OPENAPI_SK"] = ""
                redirectErrorStream(true)
            }
            .start()
        assertTrue(missingOpenApi.waitFor(5, TimeUnit.SECONDS))
        assertNotEquals(0, missingOpenApi.exitValue())

        val (openApiServer, openApiCalls) = startMockOpenApiServer()
        val (process, baseUrl) = startNodeServer("http://127.0.0.1:${openApiServer.address.port}")
        val client = HttpGroupCloudClient(baseUrl)
        try {
            val hostId = UUID.randomUUID().toString()
            val hostRun = UUID.randomUUID().toString()
            val created = client.createHybridRoom(hostId, hostRun, "队长A", requireApproval = false)
            assertEquals(GroupMode.HYBRID, created.snapshot.view.mode)
            assertEquals(4, created.snapshot.view.maxMembers)
            assertEquals(GroupTransport.LOCAL, created.snapshot.hybridTransport?.active)
            assertEquals(1L, created.snapshot.hybridTransport?.epoch)
            assertNotNull(created.snapshot.hybridTransport?.localCode)
            assertNotNull(created.snapshot.hybridTransport?.localP2pSsid)
            assertNotNull(created.snapshot.hybridTransport?.localP2pPassphrase)
            assertNull(created.rtc)
            assertNull(created.snapshot.rtcRoomId)
            val invite = GroupCloudInviteCode(created.snapshot.inviteCode!!)
            val roomId = created.roomId

            val deniedLocalRtc = assertThrows(GroupCloudException::class.java) {
                client.refreshRtcToken(created.sessionToken, roomId)
            }
            assertEquals("TRANSPORT_NOT_CLOUD", deniedLocalRtc.code)

            val peerBId = UUID.randomUUID().toString()
            val peerB = client.submitJoinRequest(invite, peerBId, UUID.randomUUID().toString(), "头车B")
                as CloudJoinSubmission.Admitted
            val peerCId = UUID.randomUUID().toString()
            val peerC = client.submitJoinRequest(invite, peerCId, UUID.randomUUID().toString(), "队员C")
                as CloudJoinSubmission.Admitted
            val peerDId = UUID.randomUUID().toString()
            client.submitJoinRequest(invite, peerDId, UUID.randomUUID().toString(), "尾车D")
                as CloudJoinSubmission.Admitted

            // 5th rider MUST be rejected (hard cap 4 even when server NETWORK maxMembers = 50).
            try {
                client.submitJoinRequest(invite, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "第5人")
                fail("Expected ROOM_FULL for 5th member in HYBRID room")
            } catch (error: GroupCloudException) {
                assertEquals("ROOM_FULL", error.code)
            }

            val latestAfterFour = client.pollEvents(created.sessionToken, roomId, created.latestSeq, 0)
            var rev = latestAfterFour.events.filterIsInstance<GroupCloudEvent.Snapshot>().last().payload.view.revision

            // Promote B to HEAD, switch B to RIDER -> in LOCAL transport, rtc remains null.
            val promoted = client.setRole(created.sessionToken, roomId, peerBId, GroupRole.HEAD, rev)
            rev = promoted.view.revision
            val toRider = client.switchChannel(peerB.session.sessionToken, roomId, GroupChannel.Rider, rev)
            assertNull(toRider.rtc)
            rev = toRider.snapshot.view.revision

            // Disable riderIncludesFormation -> B returns to PUBLIC.
            val policyOff = client.setRiderIncludesFormation(created.sessionToken, roomId, false, rev)
            assertEquals(GroupChannel.Public, policyOff.view.members.single { it.lease.deviceId == peerBId }.channel)
            rev = policyOff.view.revision

            // Peer triggers whole-group fallback LOCAL -> CLOUD -> epoch 2 mints RTC token.
            val switchedToCloud = client.switchTransport(
                peerB.session.sessionToken,
                roomId,
                GroupTransport.CLOUD,
                "LOCAL_LINK_LOST",
                null,
                1L
            )
            assertTrue(switchedToCloud.switched)
            assertEquals(GroupTransport.CLOUD, switchedToCloud.snapshot.hybridTransport?.active)
            assertEquals(2L, switchedToCloud.snapshot.hybridTransport?.epoch)
            assertNull(switchedToCloud.snapshot.hybridTransport?.localCode)
            assertTrue(checkNotNull(switchedToCloud.rtc).rtcRoomId.endsWith("_e2_pub"))
            rev = switchedToCloud.snapshot.view.revision

            // Fetch C's PUBLIC token, then enter PRIVATE -> server revokes both B and C old PUBLIC tokens.
            val peerBSeat = switchedToCloud.snapshot.view.members.single { it.lease.deviceId == peerBId }.rtcUserId
            val peerCPubToken = client.refreshRtcToken(peerC.session.sessionToken, roomId)
            assertTrue(peerCPubToken.rtcRoomId.endsWith("_e2_pub"))
            val req = client.requestPrivate(peerB.session.sessionToken, roomId, peerCId, rev)
            rev = req.snapshot.view.revision
            val accepted = client.acceptPrivate(peerC.session.sessionToken, roomId, req.inviteId, rev)
            assertTrue(accepted.snapshot.view.members.single { it.lease.deviceId == peerCId }.channel is GroupChannel.Private)
            val privRtc = checkNotNull(accepted.rtc)
            assertTrue(privRtc.rtcRoomId.contains("_e2_p_"))
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.body.optString("UserId") == peerBSeat && it.body.optString("RoomId").endsWith("_e2_pub") })
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.body.optString("UserId") == peerCPubToken.rtcUserId && it.body.optString("RoomId").endsWith("_e2_pub") })
            rev = accepted.snapshot.view.revision
            val closed = client.closePrivate(peerB.session.sessionToken, roomId, rev)
            assertEquals(GroupChannel.Public, closed.snapshot.view.members.single { it.lease.deviceId == peerCId }.channel)
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.body.optString("RoomId") == privRtc.rtcRoomId && !it.body.has("UserId") })
            assertTrue(openApiCalls.any { it.action == "UpdateBanRoomUserRule" && it.body.optString("UserId") == peerBSeat && it.body.optString("RoomId").endsWith("_e2_pub") })
            rev = closed.snapshot.view.revision

            // Owner manually switches whole group back to LOCAL -> bans epoch-2 RTC room and returns rtc=null.
            val backToLocal = client.switchTransport(
                created.sessionToken,
                roomId,
                GroupTransport.LOCAL,
                "MANUAL_LOCAL",
                rev,
                2L
            )
            assertTrue(backToLocal.switched)
            assertEquals(GroupTransport.LOCAL, backToLocal.snapshot.hybridTransport?.active)
            assertEquals(3L, backToLocal.snapshot.hybridTransport?.epoch)
            assertNotNull(backToLocal.snapshot.hybridTransport?.localCode)
            assertNull(backToLocal.rtc)
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.body.optString("RoomId").endsWith("_e2_pub") && !it.body.has("UserId") })
            rev = backToLocal.snapshot.view.revision

            client.endRoom(created.sessionToken, roomId, rev)
        } finally {
            client.close()
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            openApiServer.stop(0)
        }
    }
}
