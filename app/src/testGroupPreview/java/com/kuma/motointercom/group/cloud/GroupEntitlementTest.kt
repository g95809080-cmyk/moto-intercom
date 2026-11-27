package com.kuma.motointercom.group.cloud

import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.ui.redactDiagnosticLine
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupEntitlementTest {
    private fun findServerScript(): File {
        val candidates = listOf(
            File("tools/group-cloud/server.mjs"),
            File("../tools/group-cloud/server.mjs")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate tools/group-cloud/server.mjs from ${File(".").absolutePath}")
    }

    private fun startMockOpenApiServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val action = Regex("Action=([^&]+)").find(query)?.groupValues?.get(1).orEmpty()
            exchange.requestBody.bufferedReader().use { it.readText() }
            val responseBytes = """{"ResponseMetadata":{"Action":"$action"},"Result":{"Message":"success"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()
        return server
    }

    private fun postAdminJson(baseUrl: String, adminToken: String, path: String, body: JSONObject): JSONObject {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $adminToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(bytes) }
        assertEquals(201, conn.responseCode)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }

    @Test
    fun redactDiagnosticLineMasksActivationCodeAndEntitlementToken() {
        val lines = listOf(
            "X-Moto-Entitlement: mte_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "redeem activation_code=MOTO-7K9P-4X2M-Q8VN ok",
            "standalone MOTO-AB12-CD34-EF56 and mte_1234567890abcdef123456"
        )
        val joined = lines.joinToString("\n", transform = ::redactDiagnosticLine)
        assertFalse(joined.contains("mte_AbCdEf"))
        assertFalse(joined.contains("MOTO-7K9P"))
        assertFalse(joined.contains("MOTO-AB12"))
        assertFalse(joined.contains("mte_123456"))
    }

    @Test
    fun entitlementStorePersistsAndClearsSummaryAndToken() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = GroupEntitlementStore(ctx)
        store.clear()
        assertNull(store.loadToken())
        assertNull(store.loadSummary())

        val summary = GroupEntitlementSummary(
            entitlementId = "ent_test_1",
            status = "ACTIVE",
            remainingSeconds = 18_000L,
            remainingMinutes = 300,
            totalMinutes = 300,
            usedMinutes = 0,
            maxMembers = 6,
            maxConcurrentRooms = 1,
            maxSessionMinutes = 120,
            nearestExpiresAtMs = 1_900_000_000_000L,
            furthestExpiresAtMs = 1_900_000_000_000L,
            activeGrantCount = 1,
            codeMask = "MOTO-****-****-Q8VN",
            serverNowMs = 1_899_000_000_000L
        )
        store.saveRedeemResult(
            GroupEntitlementRedeemResult(
                action = "ACTIVATED",
                rebound = false,
                entitlementToken = "mte_secret_token_value_1234567890",
                codeMask = "MOTO-****-****-Q8VN",
                summary = summary
            )
        )
        // P1-2 regression: raw SharedPreferences must never contain plaintext entitlement token
        val rawPrefs = ctx.getSharedPreferences("motocom_group_entitlement_prefs", android.content.Context.MODE_PRIVATE)
        val allValues = rawPrefs.all.values.joinToString("|") { it.toString() }
        assertFalse("Plaintext entitlement token leaked into SharedPreferences", allValues.contains("mte_secret_token_value_1234567890"))
        assertNull(rawPrefs.getString("entitlement_token", null))
        assertTrue(rawPrefs.getString("entitlement_token_enc", "")!!.startsWith("v1:"))

        assertEquals("mte_secret_token_value_1234567890", store.loadToken())
        val loaded = store.loadSummary()
        assertNotNull(loaded)
        assertEquals(300, loaded!!.remainingMinutes)
        assertEquals(6, loaded.maxMembers)
        assertEquals("MOTO-****-****-Q8VN", loaded.codeMask)
        assertTrue(loaded.isUsable)

        // Corrupted ciphertext fails closed and clears stored ciphertext
        rawPrefs.edit().putString("entitlement_token_enc", "v1:corrupted-base64!!!").commit()
        assertNull(store.loadToken())
        assertNull(rawPrefs.getString("entitlement_token_enc", null))

        // Failing cipher must never fall back to plaintext storage
        val brokenStore = GroupEntitlementStore(ctx, object : EntitlementTokenCipher {
            override fun encrypt(plaintext: String): String = throw IllegalStateException("keystore unavailable")
            override fun decrypt(ciphertext: String): String? = null
        })
        assertThrows(IllegalStateException::class.java) {
            brokenStore.saveRedeemResult(
                GroupEntitlementRedeemResult(
                    action = "ACTIVATED",
                    rebound = false,
                    entitlementToken = "mte_never_plaintext_9876543210",
                    codeMask = "MOTO-****-****-Q8VN",
                    summary = summary
                )
            )
        }
        assertFalse(rawPrefs.all.values.joinToString("|").contains("mte_never_plaintext_9876543210"))

        store.clear()
        assertNull(store.loadToken())
        assertNull(store.loadSummary())
    }

    @Test
    fun androidClientEndToEndRedeemCreateAndFreeMemberJoinAgainstLiveNodeServer() {
        // Fail-closed startup when entitlement is enabled (default) without codeHmacSecret
        val missingSecret = ProcessBuilder("node", findServerScript().canonicalFile.absolutePath)
            .apply {
                environment()["GROUP_CLOUD_HOST"] = "127.0.0.1"
                environment()["GROUP_CLOUD_PORT"] = "0"
                environment()["GROUP_CLOUD_VOLC_APP_ID"] = "e2e-ent-app"
                environment()["GROUP_CLOUD_VOLC_APP_KEY"] = "e2e-ent-key"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_AK"] = "e2e-ent-ak"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_SK"] = "e2e-ent-sk"
                environment()["GROUP_CLOUD_CODE_HMAC_SECRET"] = ""
                redirectErrorStream(true)
            }
            .start()
        assertTrue(missingSecret.waitFor(5, TimeUnit.SECONDS))
        assertNotEquals(0, missingSecret.exitValue())

        val openApi = startMockOpenApiServer()
        val port = ServerSocket(0).use { it.localPort }
        val dbFile = File.createTempFile("moto-ent-e2e-", ".db")
        dbFile.delete()
        val adminToken = "e2e-admin-secret-token-0123456789abcdef"
        val process = ProcessBuilder("node", findServerScript().canonicalFile.absolutePath)
            .apply {
                environment()["GROUP_CLOUD_HOST"] = "127.0.0.1"
                environment()["GROUP_CLOUD_PORT"] = port.toString()
                environment()["GROUP_CLOUD_MAX_MEMBERS"] = "50"
                environment()["GROUP_CLOUD_VOLC_APP_ID"] = "e2e-ent-app"
                environment()["GROUP_CLOUD_VOLC_APP_KEY"] = "e2e-ent-key"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_AK"] = "e2e-ent-ak"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_SK"] = "e2e-ent-sk"
                environment()["GROUP_CLOUD_VOLC_OPENAPI_ENDPOINT"] = "http://127.0.0.1:${openApi.address.port}"
                environment()["GROUP_CLOUD_DB_PATH"] = dbFile.absolutePath
                environment()["GROUP_CLOUD_CODE_HMAC_SECRET"] = "0123456789abcdef0123456789abcdef"
                environment()["GROUP_CLOUD_ADMIN_TOKEN"] = adminToken
                environment()["GROUP_CLOUD_GLOBAL_MONTHLY_LIMIT_MINUTES"] = "8500"
                redirectErrorStream(true)
            }
            .start()
        val baseUrl = "http://127.0.0.1:$port"
        try {
            val deadline = System.currentTimeMillis() + 8_000L
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    val conn = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
                    conn.connectTimeout = 400
                    conn.readTimeout = 400
                    if (conn.responseCode == 200) {
                        ready = true
                        conn.disconnect()
                        break
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }
            assertTrue("Node entitlement server did not start in time", ready)

            // Admin mints one activation code
            val minted = postAdminJson(
                baseUrl,
                adminToken,
                "/admin/v1/codes/batch",
                JSONObject()
                    .put("count", 1)
                    .put("quotaMinutes", 300)
                    .put("validDays", 14)
                    .put("maxMembers", 6)
            )
            val rawCode = minted.getJSONArray("codes").getJSONObject(0).getString("code")
            // Verify Kotlin checksum validator accepts the Node-generated code
            assertEquals(rawCode, GroupActivationCodeValidator.normalizeOrNull(rawCode.lowercase()))

            // Host without entitlement cannot create NETWORK or HYBRID room
            val unauthClient = HttpGroupCloudClient(baseUrl)
            val hostId = UUID.randomUUID().toString()
            val unauthNetErr = assertThrows(GroupCloudException::class.java) {
                unauthClient.createRoom(hostId, UUID.randomUUID().toString(), "Host", false)
            }
            assertEquals("ENTITLEMENT_REQUIRED", unauthNetErr.code)
            val unauthHybridErr = assertThrows(GroupCloudException::class.java) {
                unauthClient.createHybridRoom(hostId, UUID.randomUUID().toString(), "Host", false)
            }
            assertEquals("ENTITLEMENT_REQUIRED", unauthHybridErr.code)

            // Host redeems activation code via HttpGroupCloudClient
            val redeemed = unauthClient.redeemActivationCode(rawCode, hostId, "2.3.0-r5.preview.10")
            assertEquals("ACTIVATED", redeemed.action)
            assertEquals(300, redeemed.summary.remainingMinutes)
            assertEquals(6, redeemed.summary.maxMembers)
            unauthClient.close()

            // Host with entitlement creates HYBRID room, member joins WITHOUT activation code
            val missingDeviceClient = HttpGroupCloudClient(baseUrl, entitlementTokenProvider = { redeemed.entitlementToken })
            val missingDeviceErr = assertThrows(GroupCloudException::class.java) {
                missingDeviceClient.fetchEntitlementStatus()
            }
            assertEquals("DEVICE_ID_REQUIRED", missingDeviceErr.code)
            missingDeviceClient.close()

            val hostClient = HttpGroupCloudClient(
                baseUrl,
                entitlementTokenProvider = { redeemed.entitlementToken },
                deviceIdProvider = { hostId }
            )
            val status = hostClient.fetchEntitlementStatus()
            assertEquals(300, status.remainingMinutes)

            val created = hostClient.createHybridRoom(hostId, UUID.randomUUID().toString(), "Host", false)
            val invite = requireNotNull(created.snapshot.inviteCode)
            val peerClient = HttpGroupCloudClient(baseUrl)
            val joined = peerClient.submitJoinRequest(GroupCloudInviteCode(invite), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Peer")
            assertTrue(joined is CloudJoinSubmission.Admitted)

            hostClient.endRoom(created.sessionToken, created.roomId, (joined as CloudJoinSubmission.Admitted).session.snapshot.view.revision)
            peerClient.close()
            hostClient.close()
        } finally {
            process.destroyForcibly()
            openApi.stop(0)
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
