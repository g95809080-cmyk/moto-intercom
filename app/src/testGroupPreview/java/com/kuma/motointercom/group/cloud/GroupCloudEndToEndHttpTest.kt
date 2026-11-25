package com.kuma.motointercom.group.cloud

import com.kuma.motointercom.group.*
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
import java.util.concurrent.TimeUnit

/** End-to-end wire test: Android HttpGroupCloudClient against live Node tools/group-cloud/server.mjs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupCloudEndToEndHttpTest {
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
                environment()["GROUP_CLOUD_VOLC_APP_ID"] = "e2e-volc-app"
                environment()["GROUP_CLOUD_VOLC_APP_KEY"] = "e2e-volc-app-key"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_AK"] = "e2e-openapi-ak"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_SK"] = "e2e-openapi-sk"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_ENDPOINT"] = openApiEndpoint
                redirectErrorStream(true)
            }
            .start()
        val output = StringBuilder()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
            }
        }.apply {
            name = "node-group-cloud-output"
            isDaemon = true
            start()
        }
        val baseUrl = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                val conn = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                conn.responseCode == 200 &&
                    conn.inputStream.bufferedReader().use { it.readText() }.contains("\"maxMembers\":50")
            }.getOrDefault(false)
            if (ok) return process to baseUrl
            Thread.sleep(100)
        }
        process.destroyForcibly()
        error(
            "Node group-cloud server failed to start on $baseUrl; " +
                "script=${script.absolutePath}; output=${synchronized(output) { output.toString() }}"
        )
    }

    @Test
    fun kotlinClientCompletesApprovalRtcRefreshTransferAndDisbandFlow() {
        val (openApiServer, openApiCalls) = startMockOpenApiServer()
        val (process, baseUrl) = startNodeServer("http://127.0.0.1:${openApiServer.address.port}")
        val ownerClient = HttpGroupCloudClient(baseUrl)
        val peerClients = (2..4).map { HttpGroupCloudClient(baseUrl) }
        try {
            val created = ownerClient.createRoom("dev-1", "run-1", "队长", requireApproval = true)
            val invite = GroupCloudInviteCode(checkNotNull(created.snapshot.inviteCode))
            val createdRtc = checkNotNull(created.rtc)
            assertEquals("e2e-volc-app", createdRtc.appId)
            assertTrue(createdRtc.rtcToken.isNotBlank())
            assertFalse(createdRtc.toString().contains(createdRtc.rtcToken))
            assertEquals(50, created.snapshot.view.maxMembers)
            assertTrue(created.snapshot.view.links.isEmpty())
            assertFalse(created.toString().contains("e2e-volc-app-key"))

            var ownerRev = created.snapshot.view.revision
            val memberSessions = mutableListOf<CloudAdmittedSession>()
            for (idx in 2..4) {
                val client = peerClients[idx - 2]
                val submission = client.submitJoinRequest(invite, "dev-$idx", "run-$idx", "队员$idx")
                assertTrue(submission is CloudJoinSubmission.Pending)
                val pending = submission as CloudJoinSubmission.Pending
                val approved = ownerClient.approveJoinRequest(
                    created.sessionToken,
                    created.roomId,
                    pending.requestId,
                    ownerRev + 1
                )
                ownerRev = approved.view.revision
                val polled = client.pollJoinRequest(pending.requestId, pending.requestSecret, waitMs = 0)
                assertTrue("Expected admitted member, got $polled", polled is CloudJoinPollResult.Admitted)
                memberSessions += (polled as CloudJoinPollResult.Admitted).session
            }

            val finalSnapshot = memberSessions.last().snapshot
            assertEquals(4, finalSnapshot.view.members.size)
            assertTrue(finalSnapshot.view.links.isEmpty())
            assertNull(finalSnapshot.inviteCode)
            assertEquals(
                4,
                finalSnapshot.view.members.map { it.lease.deviceId }.distinct().size
            )
            val allSessions = listOf(created) + memberSessions
            assertEquals(4, allSessions.map { checkNotNull(it.rtc).rtcUserId }.distinct().size)
            assertEquals(4, allSessions.map { checkNotNull(it.rtc).rtcToken }.distinct().size)

            val refreshed = ownerClient.refreshRtcToken(created.sessionToken, created.roomId)
            assertEquals(createdRtc.rtcRoomId, refreshed.rtcRoomId)
            assertEquals(createdRtc.rtcUserId, refreshed.rtcUserId)
            assertNotEquals(createdRtc.rtcToken, refreshed.rtcToken)
            assertTrue(refreshed.isUsable(System.currentTimeMillis()))

            val denyLeave = assertThrows(GroupCloudException::class.java) {
                ownerClient.leaveRoom(created.sessionToken, created.roomId)
            }
            assertEquals("OWNER_TRANSFER_REQUIRED", denyLeave.code)

            val beforeConfirmation = assertThrows(GroupCloudException::class.java) {
                ownerClient.transferOwner(created.sessionToken, created.roomId, "dev-2", alsoLeave = true, ownerRev)
            }
            assertEquals("TARGET_NOT_ADMITTED", beforeConfirmation.code)
            val peer = memberSessions.first()
            val firstPoll = peerClients.first().pollEvents(peer.sessionToken, created.roomId, peer.latestSeq, waitMs = 0)

            val transferred = ownerClient.transferOwner(
                created.sessionToken,
                created.roomId,
                "dev-2",
                alsoLeave = true,
                ownerRev
            )
            assertTrue(transferred.left)

            val promotedBatch = peerClients[0].pollEvents(
                memberSessions[0].sessionToken,
                created.roomId,
                firstPoll.latestSeq,
                waitMs = 0
            )
            val promoted = promotedBatch.events.filterIsInstance<GroupCloudEvent.Snapshot>().last().payload
            assertEquals("dev-2", promoted.view.ownerId)
            assertNotNull(promoted.inviteCode)
            assertTrue(promoted.view.links.isEmpty())

            peerClients[0].endRoom(
                memberSessions[0].sessionToken,
                created.roomId,
                promoted.view.revision
            )
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.authorization.startsWith("HMAC-SHA256 Credential=") && it.body.optString("UserId") == createdRtc.rtcUserId })
            assertTrue(openApiCalls.any { it.action == "BanRoomUser" && it.body.optString("RoomId") == createdRtc.rtcRoomId && !it.body.has("UserId") })
            val ended = assertThrows(GroupCloudException::class.java) {
                peerClients[1].pollEvents(
                    memberSessions[1].sessionToken,
                    created.roomId,
                    0L,
                    waitMs = 0
                )
            }
            assertEquals("ROOM_ENDED", ended.code)
        } finally {
            ownerClient.close()
            peerClients.forEach { it.close() }
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            openApiServer.stop(0)
        }
    }

    @Test
    fun httpClientRejectsOversizedResponsesWithoutUnboundedBuffering() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val oversizedPayload = ("{\"pad\":\"" + "x".repeat(12_000) + "\"}").toByteArray(Charsets.UTF_8)
        server.createContext("/v1/rooms") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { out ->
                out.write(oversizedPayload)
            }
        }
        server.createContext("/v1/rooms/err/leave") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(500, oversizedPayload.size.toLong())
            exchange.responseBody.use { out ->
                out.write(oversizedPayload)
            }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val client = HttpGroupCloudClient(baseUrl, maxResponseBytes = 4_096)
        try {
            val tooLarge200 = assertThrows(GroupCloudException::class.java) {
                client.createRoom("dev-1", "run-1", "测试", false)
            }
            assertEquals("RESPONSE_TOO_LARGE", tooLarge200.code)

            val tooLarge500 = assertThrows(GroupCloudException::class.java) {
                client.leaveRoom("tok", "err")
            }
            assertEquals("RESPONSE_TOO_LARGE", tooLarge500.code)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
