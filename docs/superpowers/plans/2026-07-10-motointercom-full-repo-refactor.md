# MotoIntercom Full Repository Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the confirmed lifecycle, P2P, signaling, audio, permission, and UI defects on `ui/light-theme-redesign` while splitting oversized classes into focused, testable units.

**Architecture:** `IntercomService` remains the Android lifecycle orchestrator and uses a generation token to reject stale asynchronous work. Pure Kotlin units own signaling validation, peer selection, VOX decisions, permission policy, and bounded logs; platform coordinators own LAN discovery, Wi-Fi Direct sockets, audio routing, and the programmatic main screen.

**Tech Stack:** Kotlin, Android SDK 23–36, Gradle 9.2.1, AGP 9.0.1, JUnit 4.13.2, Gson, WebRTC, Java sockets, Wi-Fi Direct, NSD, ADB.

## Global Constraints

- Work only on `ui/light-theme-redesign`.
- Preserve one-to-one offline intercom, existing 32 kbps Opus settings, and VOX track gating through `setVolume(0.0/1.0)`.
- Keep `minSdk = 23`, `targetSdk = 36`, and `compileSdk = 36`.
- Do not add Compose, DI, architecture frameworks, cloud services, accounts, PIN/QR pairing, or a replacement WebRTC library.
- Source-IP and P2P-interface checks are transport hardening, not cryptographic peer authentication.
- Pure logic follows RED → GREEN → REFACTOR. Android-only wiring uses compile, Lint, and physical-device checks as approved in the design.
- Every task must pass its listed verification before its local commit.
- Do not push, open a PR, or publish an APK.
- Current acceptance requires automated checks plus MI 6 `9688fa60` and Xiaomi 13 `efcb9031` install/start/service/cleanup evidence.
- Two-device discovery/signaling and Xiaomi 13 Bluetooth routing require objective evidence. Human listening checks remain not run until the user confirms them.
- 2026-07-12 override: the user confirmed only MI 6 `9688fa60` is attached and no Bluetooth device is connected. For this run, MI 6 objective lifecycle/P2P checks remain required; two-device, Xiaomi 13 Bluetooth, and human-listening checks are recorded as not run and do not block the Task 9 commit.
- 2026-07-13 override: both devices are online again, with no Bluetooth device attached. This supersedes the MI 6-only matrix: two-device lifecycle/P2P/signaling checks are required; phone-audio fallback is required on both devices; Bluetooth routing and human-listening checks remain not run and do not block the Task 9 commit.

## Target File Map

**Create**

- `app/src/main/java/com/kuma/motointercom/SessionGeneration.kt` — session token ownership.
- `app/src/main/java/com/kuma/motointercom/SignalingProtocol.kt` — bounded signaling encode/decode.
- `app/src/main/java/com/kuma/motointercom/WifiDirectPeerRegistry.kt` — current peer reconciliation.
- `app/src/main/java/com/kuma/motointercom/WifiDirectSignalingSocket.kt` — P2P TCP server/client lifecycle.
- `app/src/main/java/com/kuma/motointercom/LanRiderDevice.kt` — shared LAN device value.
- `app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt` — NSD/UDP/TCP LAN ownership.
- `app/src/main/java/com/kuma/motointercom/VoxGate.kt` — pure VOX state machine.
- `app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt` — API 31+ communication-device API.
- `app/src/main/java/com/kuma/motointercom/PermissionPolicy.kt` — core versus optional permissions.
- `app/src/main/java/com/kuma/motointercom/BoundedLogBuffer.kt` — 300-line UI log.
- `app/src/main/java/com/kuma/motointercom/MainScreen.kt` — programmatic view tree and rendering.
- `app/src/main/java/com/kuma/motointercom/RippleView.kt` — connection animation.
- `app/src/main/java/com/kuma/motointercom/VisualizerView.kt` — audio-level animation.
- `scripts/verify-device-matrix.ps1` — repeatable device evidence collection.

**Modify**

- `app/build.gradle.kts`
- `app/src/main/java/com/kuma/motointercom/IntercomService.kt`
- `app/src/main/java/com/kuma/motointercom/IntercomManager.kt`
- `app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt`
- `app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt`
- `app/src/main/java/com/kuma/motointercom/AudioRouteController.kt`
- `app/src/main/java/com/kuma/motointercom/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/styles.xml`
- `app/src/main/res/values-v31/styles.xml`

---

### Task 1: Guard Every Intercom Session with a Generation Token

**Files:**

- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/kuma/motointercom/SessionGeneration.kt`
- Create: `app/src/test/java/com/kuma/motointercom/SessionGenerationTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/IntercomService.kt`

**Interfaces:**

- Produces: `SessionGeneration.Token`, `start()`, `invalidate()`, and `isCurrent(token)`.
- Produces: token-aware `IntercomService.acceptTunnel(token, ...)` for Tasks 4–7.

- [ ] **Step 1: Add JUnit and write the failing token tests**

Add to `dependencies`:

```kotlin
testImplementation("junit:junit:4.13.2")
```

Create:

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGenerationTest {
    @Test
    fun oldTokenStaysInvalidAfterRestart() {
        val sessions = SessionGeneration()
        val old = sessions.start()
        sessions.invalidate()
        val fresh = sessions.start()

        assertFalse(sessions.isCurrent(old))
        assertTrue(sessions.isCurrent(fresh))
        assertNotEquals(old, fresh)
    }

    @Test
    fun secondStartInvalidatesFirstToken() {
        val sessions = SessionGeneration()
        val first = sessions.start()
        val second = sessions.start()

        assertFalse(sessions.isCurrent(first))
        assertTrue(sessions.isCurrent(second))
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
$env:JAVA_HOME="F:\Android\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.SessionGenerationTest"
```

Expected: Kotlin compilation fails because `SessionGeneration` does not exist.

- [ ] **Step 3: Implement the minimal token owner**

```kotlin
package com.kuma.motointercom

internal class SessionGeneration {
    @JvmInline
    value class Token internal constructor(val value: Long)

    private var next = 0L
    private var current: Token? = null

    @Synchronized
    fun start(): Token = Token(++next).also { current = it }

    @Synchronized
    fun invalidate() {
        current = null
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = current == token
}
```

- [ ] **Step 4: Run GREEN**

Run the command from Step 2.

Expected: two tests pass.

- [ ] **Step 5: Integrate the token into the service**

Add:

```kotlin
private val sessions = SessionGeneration()
private var activeSession: SessionGeneration.Token? = null

private fun isSessionCurrent(token: SessionGeneration.Token): Boolean =
    running && sessions.isCurrent(token) && activeSession == token

private fun closeStaleSocket(socket: Socket): Boolean {
    return try {
        socket.close()
        false
    } catch (_: IOException) {
        false
    }
}
```

At the start of `startIntercom()`, after the existing `running` guard:

```kotlin
val token = sessions.start()
activeSession = token
running = true
```

Every callback created by that method captures `token`. Change the tunnel handoff to:

```kotlin
onTunnelReady = { targetIp, isServer, socket ->
    if (isSessionCurrent(token)) {
        onTunnelReady(token, targetIp, isServer, socket)
    } else {
        closeStaleSocket(socket)
    }
}
```

Change the handoff signatures:

```kotlin
private fun onTunnelReady(
    token: SessionGeneration.Token,
    targetIp: String,
    isServer: Boolean,
    signalingSocket: Socket
) {
    acceptTunnel(token, targetIp, isServer, signalingSocket, closeWifiDirect = false)
}

private fun acceptTunnel(
    token: SessionGeneration.Token,
    targetIp: String,
    isServer: Boolean,
    signalingSocket: Socket,
    closeWifiDirect: Boolean
): Boolean {
    if (!isSessionCurrent(token)) return closeStaleSocket(signalingSocket)
    if (!tunnelChosen.compareAndSet(false, true)) return closeStaleSocket(signalingSocket)
    if (!isSessionCurrent(token)) return closeStaleSocket(signalingSocket)
    // Existing winning-tunnel setup remains here.
    return true
}
```

Move these statements to the first lines of `stopIntercom()`, before closing any component:

```kotlin
sessions.invalidate()
activeSession = null
running = false
tunnelChosen.set(true)
```

Remove the old final `tunnelChosen.set(false)`. Reset it only in `startIntercom()` immediately after the new token is installed. Change all `onStartCommand()` return paths to `START_NOT_STICKY`. After a signal disconnect, call `stopIntercom()` and `stopSelf()`.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.SessionGenerationTest"
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/build.gradle.kts app/src/main/java/com/kuma/motointercom/SessionGeneration.kt app/src/main/java/com/kuma/motointercom/IntercomService.kt app/src/test/java/com/kuma/motointercom/SessionGenerationTest.kt
git commit -m "refactor: guard intercom sessions by generation"
```

Expected: tests and build pass; commit contains only Task 1 files.

---

### Task 2: Centralize and Bound the Signaling Protocol

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/SignalingProtocol.kt`
- Create: `app/src/test/java/com/kuma/motointercom/SignalingProtocolTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/IntercomManager.kt`

**Interfaces:**

- Consumes: Gson already present in `app/build.gradle.kts`.
- Produces: `SignalingProtocol.Message`, `decode(frame)`, and `encode(message)`.

- [ ] **Step 1: Write failing protocol tests**

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalingProtocolTest {
    @Test
    fun decodesIdentityThenExpectedOffer() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertEquals(
            SignalingProtocol.Message.Identity("Rider"),
            protocol.decode("""{"type":"IDENTITY","name":" Rider "}""".toByteArray())
        )
        val message = protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        assertEquals(SignalingProtocol.Message.Offer("""{"type":"OFFER","sdp":"v=0"}"""), message)
    }

    @Test
    fun rejectsOfferBeforeIdentity() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(
                """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
            )
        }
    }

    @Test
    fun rejectsCandidate257() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        protocol.decode("""{"type":"IDENTITY","name":"A"}""".toByteArray())
        protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        val candidate =
            """{"type":"CANDIDATE","candidate":{"sdpMid":"0","sdpMLineIndex":0,"candidate":"c"}}"""
                .toByteArray()
        repeat(256) { protocol.decode(candidate) }

        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(candidate)
        }
    }

    @Test
    fun rejectsIdentityOver64CodePoints() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        val name = "骑".repeat(65)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode("""{"type":"IDENTITY","name":"$name"}""".toByteArray())
        }
    }

    @Test
    fun rejectsFrameOver128KiB() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(ByteArray(SignalingProtocol.MAX_FRAME_BYTES + 1) { 'x'.code.toByte() })
        }
    }

    @Test
    fun rejectsCandidateOver4KiB() {
        val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
        protocol.decode("""{"type":"IDENTITY","name":"A"}""".toByteArray())
        protocol.decode(
            """{"type":"OFFER","sdp":"{\"type\":\"OFFER\",\"sdp\":\"v=0\"}"}""".toByteArray()
        )
        val longCandidate = "c".repeat(SignalingProtocol.MAX_CANDIDATE_BYTES + 1)
        assertThrows(SignalingProtocol.ProtocolException::class.java) {
            protocol.decode(
                """{"type":"CANDIDATE","candidate":{"candidate":"$longCandidate"}}""".toByteArray()
            )
        }
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.SignalingProtocolTest"
```

Expected: compilation fails because `SignalingProtocol` does not exist.

- [ ] **Step 3: Implement the protocol**

Implement these exact public members:

```kotlin
package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class SignalingProtocol(private val expectedRemoteSdp: SdpKind) {
    enum class SdpKind { OFFER, ANSWER }

    sealed interface Message {
        data class Identity(val name: String) : Message
        data class Offer(val sdpJson: String) : Message
        data class Answer(val sdpJson: String) : Message
        data class Candidate(val candidateJson: String) : Message
    }

    class ProtocolException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private var identitySeen = false
    private var sdpSeen = false
    private var candidateCount = 0

    @Synchronized
    fun decode(frame: ByteArray): Message {
        requireBytes("frame", frame.size, MAX_FRAME_BYTES)
        val root = try {
            JsonParser.parseString(String(frame, StandardCharsets.UTF_8)).asJsonObject
        } catch (t: Throwable) {
            throw ProtocolException("invalid signaling JSON", t)
        }
        val type = root.requiredString("type")
        return when (type) {
            "IDENTITY" -> decodeIdentity(root)
            "OFFER" -> decodeSdp(root, SdpKind.OFFER)
            "ANSWER" -> decodeSdp(root, SdpKind.ANSWER)
            "CANDIDATE" -> decodeCandidate(root)
            else -> throw ProtocolException("unknown signaling type: $type")
        }
    }

    fun encode(message: Message): ByteArray {
        val root = JsonObject()
        when (message) {
            is Message.Identity -> {
                root.addProperty("type", "IDENTITY")
                root.addProperty("name", message.name)
            }
            is Message.Offer -> addPayload(root, "OFFER", "sdp", message.sdpJson)
            is Message.Answer -> addPayload(root, "ANSWER", "sdp", message.sdpJson)
            is Message.Candidate -> addPayload(root, "CANDIDATE", "candidate", message.candidateJson)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            requireBytes("frame", it.size, MAX_FRAME_BYTES)
        }
    }

    private fun decodeIdentity(root: JsonObject): Message.Identity {
        if (identitySeen || sdpSeen) throw ProtocolException("identity out of order")
        val name = root.requiredString("name").trim()
        if (name.isEmpty()) throw ProtocolException("identity is empty")
        if (name.codePointCount(0, name.length) > MAX_IDENTITY_CODE_POINTS) {
            throw ProtocolException("identity is too long")
        }
        identitySeen = true
        return Message.Identity(name)
    }

    private fun decodeSdp(root: JsonObject, kind: SdpKind): Message {
        if (!identitySeen || sdpSeen || kind != expectedRemoteSdp) {
            throw ProtocolException("unexpected $kind")
        }
        val raw = root.requiredPayload("sdp")
        requireBytes("sdp", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_SDP_BYTES)
        sdpSeen = true
        return if (kind == SdpKind.OFFER) Message.Offer(raw) else Message.Answer(raw)
    }

    private fun decodeCandidate(root: JsonObject): Message.Candidate {
        if (!identitySeen || !sdpSeen) throw ProtocolException("candidate out of order")
        if (++candidateCount > MAX_CANDIDATES) throw ProtocolException("too many candidates")
        val raw = root.requiredPayload("candidate")
        requireBytes("candidate", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_CANDIDATE_BYTES)
        return Message.Candidate(raw)
    }

    private fun addPayload(root: JsonObject, type: String, key: String, raw: String) {
        root.addProperty("type", type)
        root.add(key, JsonParser.parseString(raw))
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString
            ?: throw ProtocolException("missing $key")

    private fun JsonObject.requiredPayload(key: String): String {
        val value = get(key) ?: throw ProtocolException("missing $key")
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
    }

    private fun requireBytes(name: String, actual: Int, maximum: Int) {
        if (actual !in 1..maximum) throw ProtocolException("$name bytes=$actual max=$maximum")
    }

    companion object {
        const val MAX_FRAME_BYTES = 128 * 1024
        const val MAX_SDP_BYTES = 64 * 1024
        const val MAX_CANDIDATE_BYTES = 4 * 1024
        const val MAX_CANDIDATES = 256
        const val MAX_IDENTITY_CODE_POINTS = 64
    }
}
```

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: all protocol tests pass.

- [ ] **Step 5: Route IntercomManager through the protocol**

Create one protocol per manager:

```kotlin
private val protocol = SignalingProtocol(
    if (isServer) SignalingProtocol.SdpKind.OFFER else SignalingProtocol.SdpKind.ANSWER
)
```

Change `readFrame()` to return `ByteArray` and use `SignalingProtocol.MAX_FRAME_BYTES`. Replace JSON dispatch with:

```kotlin
private fun dispatch(message: SignalingProtocol.Message) {
    when (message) {
        is SignalingProtocol.Message.Identity ->
            mainHandler.post { if (!closed.get()) onRemoteRiderIdentified(message.name) }
        is SignalingProtocol.Message.Offer ->
            audioEngineOrThrow().createAnswer(message.sdpJson)
        is SignalingProtocol.Message.Answer ->
            audioEngineOrThrow().setRemoteAnswer(message.sdpJson)
        is SignalingProtocol.Message.Candidate ->
            audioEngineOrThrow().addRemoteIceCandidate(message.candidateJson)
    }
}
```

The reader loop calls `dispatch(protocol.decode(readFrame()))`. Any non-`IOException` becomes:

```kotlin
val failure = t as? IOException ?: IOException("invalid signaling message", t)
if (!closed.get()) notifyDisconnected(failure)
```

Change sending to `sendFrame(message: SignalingProtocol.Message)` and write `protocol.encode(message)`. Check `closed` and `writer.isShutdown` before queueing. Remove `org.json.JSONObject` and the old protocol constants.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.SignalingProtocolTest"
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/src/main/java/com/kuma/motointercom/SignalingProtocol.kt app/src/main/java/com/kuma/motointercom/IntercomManager.kt app/src/test/java/com/kuma/motointercom/SignalingProtocolTest.kt
git commit -m "refactor: validate signaling protocol"
```

---

### Task 3: Reconcile Wi-Fi Direct Peers and Make State Explicit

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/WifiDirectPeerRegistry.kt`
- Create: `app/src/test/java/com/kuma/motointercom/WifiDirectPeerRegistryTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt`

**Interfaces:**

- Produces: address-only peer state independent of Android classes.
- Produces: `WifiDirectTunnel.State` used by Task 4.

- [ ] **Step 1: Write the failing registry tests**

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectPeerRegistryTest {
    @Test
    fun removesDepartedAcceptedPeerAndSelectsRemainingPeer() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A", "B"))
        peers.accept("A")
        peers.accept("B")

        val snapshot = peers.reconcile(setOf("B"))

        assertFalse(snapshot.accepted.contains("A"))
        assertEquals("B", snapshot.selected)
    }

    @Test
    fun clearsSelectionWhenNoAcceptedPeerRemains() {
        val peers = WifiDirectPeerRegistry()
        peers.reconcile(setOf("A"))
        peers.accept("A")

        val snapshot = peers.reconcile(emptySet())

        assertNull(snapshot.selected)
        assertEquals(emptySet<String>(), snapshot.pending)
        assertEquals(emptySet<String>(), snapshot.accepted)
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.WifiDirectPeerRegistryTest"
```

Expected: compilation fails because the registry does not exist.

- [ ] **Step 3: Implement the registry**

```kotlin
package com.kuma.motointercom

internal class WifiDirectPeerRegistry {
    data class Snapshot(
        val pending: Set<String>,
        val accepted: Set<String>,
        val selected: String?
    )

    private val pending = linkedSetOf<String>()
    private val accepted = linkedSetOf<String>()
    private var selected: String? = null

    @Synchronized
    fun reconcile(current: Set<String>): Snapshot {
        pending.retainAll(current)
        accepted.retainAll(current)
        if (selected !in accepted) selected = accepted.firstOrNull()
        return snapshot()
    }

    @Synchronized
    fun markPending(address: String): Snapshot {
        if (address !in accepted) pending += address
        return snapshot()
    }

    @Synchronized
    fun accept(address: String): Snapshot {
        pending -= address
        accepted += address
        if (selected == null) selected = address
        return snapshot()
    }

    @Synchronized
    fun isAccepted(address: String): Boolean = address in accepted

    @Synchronized
    fun reset() {
        pending.clear()
        accepted.clear()
        selected = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(pending.toSet(), accepted.toSet(), selected)
}
```

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: both tests pass.

- [ ] **Step 5: Integrate registry and explicit tunnel state**

In `WifiDirectTunnel` add:

```kotlin
private enum class State {
    DISCOVERING,
    P2P_CONNECTING,
    GROUP_READY,
    SIGNALING_READY,
    CLOSED
}

private val peerRegistry = WifiDirectPeerRegistry()
private val peerDevices = linkedMapOf<String, WifiP2pDevice>()
@Volatile private var state = State.DISCOVERING
```

In every complete peer-list callback:

```kotlin
val current = peers.deviceList.associateBy { it.deviceAddress.uppercase() }
peerDevices.keys.retainAll(current.keys)
peerDevices.putAll(current)
val snapshot = peerRegistry.reconcile(current.keys)
val selectedPeer = snapshot.selected?.let(peerDevices::get)
```

Replace direct writes to `pendingPeers`, `acceptedPeers`, and `selectedPeer` with registry calls. `resetDiscoveryCandidates()` calls `peerRegistry.reset()` and clears `peerDevices`. Set state transitions only at these points:

```kotlin
state = State.P2P_CONNECTING // immediately before manager.connect
state = State.GROUP_READY // after validated P2P group, before TCP starts
state = State.SIGNALING_READY // only after socket handoff
state = State.DISCOVERING // after reset/removeGroup completes
state = State.CLOSED // first line of close
```

Do not ignore a disconnected broadcast in `GROUP_READY`. Only suppress the known transient disconnect while `state == State.P2P_CONNECTING`.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.WifiDirectPeerRegistryTest"
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/src/main/java/com/kuma/motointercom/WifiDirectPeerRegistry.kt app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt app/src/test/java/com/kuma/motointercom/WifiDirectPeerRegistryTest.kt
git commit -m "refactor: reconcile Wi-Fi Direct peer state"
```

---

### Task 4: Extract Wi-Fi Direct Signaling Sockets

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/WifiDirectSignalingSocket.kt`
- Create: `app/src/test/java/com/kuma/motointercom/WifiDirectSignalingSocketTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt`

**Interfaces:**

- Consumes: `SessionGeneration` guard through `isSessionCurrent`.
- Produces: a single ownership-transferred `Socket` or a terminal `IOException`.

- [ ] **Step 1: Write failing loopback lifecycle tests**

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WifiDirectSignalingSocketTest {
    @Test
    fun staleSessionClosesSocketWithoutReadyCallback() {
        var active = true
        var ready = false
        val failed = CountDownLatch(1)
        val probe = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val transport = WifiDirectSignalingSocket(
            port = probe.localPort,
            readyTimeoutMillis = 1_000,
            connectTimeoutMillis = 500,
            retryDelayMillis = 10,
            isSessionCurrent = { active },
            onReady = { _, _, socket -> ready = true; socket.close() },
            onFailure = { failed.countDown() }
        )
        probe.close()
        active = false

        transport.startClient(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress())
        failed.await(2, TimeUnit.SECONDS)
        transport.close()

        assertFalse(ready)
    }

    @Test
    fun serverHandsOffOneAllowedLoopbackPeer() {
        val ready = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { true },
            { _, server, socket -> if (server) ready.countDown(); socket.close() },
            { }
        )
        val loopback = InetAddress.getLoopbackAddress()
        transport.startServer(loopback) { it.isLoopbackAddress }
        transport.startClient(loopback, loopback)

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        transport.close()
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.WifiDirectSignalingSocketTest"
```

Expected: compilation fails because the socket transport does not exist.

- [ ] **Step 3: Implement the socket owner**

Implement:

```kotlin
internal class WifiDirectSignalingSocket(
    private val port: Int,
    private val readyTimeoutMillis: Long,
    private val connectTimeoutMillis: Int,
    private val retryDelayMillis: Long,
    private val isSessionCurrent: () -> Boolean,
    private val onReady: (String, Boolean, Socket) -> Unit,
    private val onFailure: (IOException) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val io = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var connectingSocket: Socket? = null

    fun startServer(localAddress: InetAddress, remoteAllowed: (InetAddress) -> Boolean) {
        io.execute {
            val deadline = System.currentTimeMillis() + readyTimeoutMillis
            try {
                val server = ServerSocket().also { serverSocket = it }
                server.reuseAddress = true
                server.soTimeout = 500
                server.bind(InetSocketAddress(localAddress, port))
                while (isUsable() && System.currentTimeMillis() < deadline) {
                    try {
                        val socket = server.accept()
                        if (remoteAllowed(socket.inetAddress)) return@execute handoff(socket, true)
                        socket.close()
                    } catch (_: SocketTimeoutException) {
                    }
                }
                fail(IOException("signaling accept timeout"))
            } catch (t: Throwable) {
                fail(t.asIo("signaling server failed"))
            }
        }
    }

    fun startClient(localAddress: InetAddress, remoteAddress: InetAddress) {
        io.execute {
            val deadline = System.currentTimeMillis() + readyTimeoutMillis
            var last: IOException = IOException("signaling connect timeout")
            while (isUsable() && System.currentTimeMillis() < deadline) {
                var socket: Socket? = Socket()
                try {
                    connectingSocket = socket
                    socket.bind(InetSocketAddress(localAddress, 0))
                    socket.connect(InetSocketAddress(remoteAddress, port), connectTimeoutMillis)
                    val connected = socket
                    socket = null
                    connectingSocket = null
                    return@execute handoff(connected, false)
                } catch (t: Throwable) {
                    last = t.asIo("signaling client failed")
                } finally {
                    connectingSocket = null
                    socket?.close()
                }
                Thread.sleep(retryDelayMillis)
            }
            fail(last)
        }
    }

    private fun handoff(socket: Socket, server: Boolean) {
        if (!isUsable()) return socket.close()
        try {
            onReady(socket.inetAddress.hostAddress.orEmpty(), server, socket)
        } catch (t: Throwable) {
            socket.close()
            fail(t.asIo("signaling handoff failed"))
        }
    }

    private fun isUsable(): Boolean = !closed.get() && isSessionCurrent()

    private fun fail(error: IOException) {
        if (isUsable()) onFailure(error)
    }

    private fun Throwable.asIo(message: String): IOException =
        this as? IOException ?: IOException(message, this)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        serverSocket?.close()
        connectingSocket?.close()
        io.shutdownNow()
    }
}
```

Add the necessary `java.io`, `java.net`, `java.util.concurrent`, and `AtomicBoolean` imports.

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: both loopback tests pass without leaked test processes.

- [ ] **Step 5: Replace tunnel socket code**

`WifiDirectTunnel` owns one nullable `WifiDirectSignalingSocket`. Remove its raw IO executor, server accept loop, client loop, and raw server/signaling socket fields. Create the transport only after group validation:

```kotlin
socketTransport = WifiDirectSignalingSocket(
    port = SIGNALING_PORT,
    readyTimeoutMillis = CONNECT_WATCHDOG_MS,
    connectTimeoutMillis = SOCKET_CONNECT_TIMEOUT_MS,
    retryDelayMillis = SOCKET_RETRY_DELAY_MS,
    isSessionCurrent = { running.get() && isSessionCurrent() && state != State.CLOSED },
    onReady = { ip, server, socket ->
        state = State.SIGNALING_READY
        connectingAddress = null
        cancelConnectWatchdog()
        postReady(ip, server, socket)
    },
    onFailure = {
        postError(it)
        removeGroupAndRediscover("signaling socket failure")
    }
)
```

Server binds `InetAddress.getByName(localP2pIp(group.interfaceName))`. Accept only when the group contains exactly the selected client and the accepted socket terminates on that bound P2P interface. Client binds the same local P2P address and connects exactly to `groupOwnerAddress`. Do not read `/proc/net/arp`.

`close()` and `resetTunnelOnly()` close the transport. The connect watchdog remains active through `GROUP_READY` and is cancelled only at `SIGNALING_READY`. Guard `channel.close()` with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1`.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.WifiDirectSignalingSocketTest"
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/src/main/java/com/kuma/motointercom/WifiDirectSignalingSocket.kt app/src/main/java/com/kuma/motointercom/WifiDirectTunnel.kt app/src/test/java/com/kuma/motointercom/WifiDirectSignalingSocketTest.kt
git commit -m "refactor: split Wi-Fi Direct socket lifecycle"
```

---

### Task 5: Extract LAN Discovery from IntercomService

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/LanRiderDevice.kt`
- Create: `app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt`
- Create: `app/src/test/java/com/kuma/motointercom/LanDiscoveryCoordinatorTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/IntercomService.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/MainActivity.kt`

**Interfaces:**

- Consumes: `SessionGeneration.Token` and `IntercomService.acceptTunnel(token, ...)`.
- Produces: `LanRiderDevice` and one LAN socket handoff.

- [ ] **Step 1: Write the failing deterministic-client test**

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryCoordinatorTest {
    @Test
    fun higherIpv4AddressInitiatesClient() {
        assertTrue(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.20", "192.168.1.10"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.20"))
        assertFalse(LanDiscoveryCoordinator.shouldInitiateClient("192.168.1.10", "192.168.1.10"))
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.LanDiscoveryCoordinatorTest"
```

Expected: compilation fails because the coordinator does not exist.

- [ ] **Step 3: Create the LAN value and coordinator boundary**

```kotlin
package com.kuma.motointercom

internal data class LanRiderDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int
)
```

The coordinator constructor and public methods are:

```kotlin
internal class LanDiscoveryCoordinator(
    context: Context,
    private val token: SessionGeneration.Token,
    private val isSessionCurrent: (SessionGeneration.Token) -> Boolean,
    private val nodeId: String,
    private val riderName: String,
    private val onDevicesChanged: (List<LanRiderDevice>) -> Unit,
    private val onTunnelReady: (String, Boolean, Socket) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {
    fun start()
    fun connect(device: LanRiderDevice)
    override fun close()

    companion object {
        internal fun shouldInitiateClient(localIp: String, peerIp: String): Boolean =
            ipv4Value(localIp) > ipv4Value(peerIp)

        private fun ipv4Value(ip: String): Long =
            ip.split('.').fold(0L) { value, part -> (value shl 8) + part.toLong() }
    }
}
```

Move these exact existing symbols from `IntercomService` into this class without changing ports or broadcast intervals: `startLanDiscovery`, `startNsdDiscovery`, `resolveNsdService`, `runLanTcpServer`, `runLanUdpListener`, `runLanUdpBroadcaster`, `handleLanBroadcast`, `stopLanDiscovery`, `stopNsdDiscovery`, `rememberLanDevice`, `removeLanDevice`, `publishLanDevices`, `lanDevicesSnapshot`, `attributeString`, `resolvedHostAddress`, `localWifiIp`, and `compareIpv4`. Move their NSD listeners, UDP/TCP sockets, executor, and device map with them. Replace calls to service publishing methods with the constructor callbacks shown above. Every callback and socket handoff first runs:

```kotlin
private fun isActive(): Boolean = !closed.get() && isSessionCurrent(token)

private fun handoff(ip: String, server: Boolean, socket: Socket) {
    if (!isActive()) {
        socket.close()
        return
    }
    onTunnelReady(ip, server, socket)
}
```

All locally created sockets use `try/finally` until ownership transfers. `close()` sets `closed` first, closes UDP/server sockets, stops NSD, shuts down the executor, clears devices, and publishes one empty snapshot.

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: the ordering test passes.

- [ ] **Step 5: Wire the coordinator into the service**

Replace all LAN/NSD fields with:

```kotlin
private var lanDiscovery: LanDiscoveryCoordinator? = null
```

Create it per session:

```kotlin
lanDiscovery = LanDiscoveryCoordinator(
    context = this,
    token = token,
    isSessionCurrent = ::isSessionCurrent,
    nodeId = lanNodeId,
    riderName = requestedRiderName.ifBlank { "骑士" },
    onDevicesChanged = { devices ->
        if (isSessionCurrent(token)) mainHandler.post { listener?.onLanDevicesChanged(devices) }
    },
    onTunnelReady = { ip, server, socket ->
        acceptTunnel(token, ip, server, socket, closeWifiDirect = true)
    },
    onLog = ::publishLog,
    onError = ::handleError
).also { it.start() }
```

`connectToLanDevice(device)` delegates to `lanDiscovery?.connect(device)`. A winning tunnel closes `lanDiscovery`. `stopIntercom()` closes it after invalidating the token. Move `LanRiderDevice` references in `MainActivity` and `Listener` to the top-level type.

- [ ] **Step 6: Verify extraction and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.LanDiscoveryCoordinatorTest"
.\gradlew.bat :app:assembleDebug
rg "NsdManager|DatagramSocket|ServerSocket|lanExecutor|lanUdpSocket" app/src/main/java/com/kuma/motointercom/IntercomService.kt
git diff --check
```

Expected: tests/build pass; `rg` has no matches.

```powershell
git add app/src/main/java/com/kuma/motointercom/LanRiderDevice.kt app/src/main/java/com/kuma/motointercom/LanDiscoveryCoordinator.kt app/src/main/java/com/kuma/motointercom/IntercomService.kt app/src/main/java/com/kuma/motointercom/MainActivity.kt app/src/test/java/com/kuma/motointercom/LanDiscoveryCoordinatorTest.kt
git commit -m "refactor: isolate LAN discovery by session"
```

---

### Task 6: Extract VOX and Serialize WebRTC State

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/VoxGate.kt`
- Create: `app/src/test/java/com/kuma/motointercom/VoxGateTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/IntercomManager.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/IntercomService.kt`

**Interfaces:**

- Consumes: session-current lambda from Task 1.
- Produces: `VoxGate.Decision` and token-aware `RiderAudioEngine` callbacks.

- [ ] **Step 1: Write failing VOX tests**

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxGateTest {
    @Test
    fun opensHangsOverAndCloses() {
        val gate = VoxGate(enabled = true)
        assertEquals(VoxGate.State.LISTENING, gate.update(20.0, 0).state)
        gate.update(60.0, 501)
        assertEquals(VoxGate.State.OPEN, gate.update(60.0, 526).state)
        assertEquals(VoxGate.State.HANGOVER, gate.update(0.0, 646).state)
        assertEquals(VoxGate.State.LISTENING, gate.update(0.0, 1_346).state)
    }

    @Test
    fun bypassAlwaysKeepsTrackOpen() {
        val decision = VoxGate(enabled = false).update(0.0, 0)
        assertEquals(VoxGate.State.BYPASS, decision.state)
        assertEquals(1.0, decision.trackVolume, 0.0)
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.VoxGateTest"
```

Expected: compilation fails because `VoxGate` does not exist.

- [ ] **Step 3: Move the existing state machine into VoxGate**

Create:

```kotlin
internal class VoxGate(private val enabled: Boolean) {
    enum class State { BYPASS, LISTENING, OPEN, HANGOVER }

    data class Decision(
        val state: State,
        val trackVolume: Double,
        val stateChanged: Boolean,
        val openThreshold: Double,
        val closeThreshold: Double,
        val noiseFloor: Double
    )

    private var state = if (enabled) State.LISTENING else State.BYPASS
    private var noiseFloor = 32.0
    private var calibrationUntil = 0L
    private var attackStartedAt = 0L
    private var aboveCloseStartedAt = 0L
    private var lastVoiceAt = 0L
    private var hangoverStartedAt = 0L

    @Synchronized
    fun update(level: Double, nowMs: Long): Decision {
        if (!enabled) return decision(State.BYPASS, false)
        if (calibrationUntil == 0L) calibrationUntil = nowMs + 500L
        var open = maxOf(40.0, noiseFloor + 8.0)
        var close = open - 5.0
        val before = state
        when (state) {
            State.BYPASS -> Unit
            State.LISTENING -> {
                val calibrating = nowMs < calibrationUntil
                val alpha = if (calibrating) 0.10 else 0.02
                if (calibrating || level < open) {
                    noiseFloor = (noiseFloor + alpha * (level - noiseFloor)).coerceIn(20.0, 55.0)
                    open = maxOf(40.0, noiseFloor + 8.0)
                    close = open - 5.0
                }
                if (!calibrating && level >= open) {
                    if (attackStartedAt == 0L) attackStartedAt = nowMs
                    if (nowMs - attackStartedAt >= 25L) {
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                        attackStartedAt = 0L
                    }
                } else {
                    attackStartedAt = 0L
                }
            }
            State.OPEN -> {
                if (level >= close) {
                    if (aboveCloseStartedAt == 0L) aboveCloseStartedAt = nowMs
                    if (nowMs - aboveCloseStartedAt >= 25L) lastVoiceAt = nowMs
                } else {
                    aboveCloseStartedAt = 0L
                }
                if (nowMs - lastVoiceAt >= 120L) {
                    state = State.HANGOVER
                    hangoverStartedAt = nowMs
                }
            }
            State.HANGOVER -> {
                if (level >= open) {
                    if (attackStartedAt == 0L) attackStartedAt = nowMs
                    if (nowMs - attackStartedAt >= 25L) {
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                        hangoverStartedAt = 0L
                    }
                } else {
                    attackStartedAt = 0L
                }
                if (state == State.HANGOVER && nowMs - hangoverStartedAt >= 700L) {
                    state = State.LISTENING
                    aboveCloseStartedAt = 0L
                    lastVoiceAt = 0L
                    hangoverStartedAt = 0L
                }
            }
        }
        return Decision(
            state = state,
            trackVolume = if (state == State.LISTENING) 0.0 else 1.0,
            stateChanged = before != state,
            openThreshold = open,
            closeThreshold = close,
            noiseFloor = noiseFloor
        )
    }

    private fun decision(value: State, changed: Boolean) = Decision(
        value,
        if (value == State.LISTENING) 0.0 else 1.0,
        changed,
        40.0,
        35.0,
        noiseFloor
    )
}
```

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: both tests pass.

- [ ] **Step 5: Use VoxGate and serialize every WebRTC callback**

Replace VOX mutable fields and transition methods in `RiderAudioEngine` with one `VoxGate`. In the samples callback:

```kotlin
val decision = voxGate.update(energy, SystemClock.elapsedRealtime())
if (decision.stateChanged) {
    runRtc { localAudioTrack?.setVolume(decision.trackVolume) }
}
postAudioLevel(energy)
```

Add `private val isSessionCurrent: () -> Boolean` to `RiderAudioEngine` and `IntercomManager` constructors. Use:

```kotlin
private fun postMain(block: () -> Unit) {
    mainHandler.post {
        if (!closed.get() && isSessionCurrent()) block()
    }
}
```

Use this initialization state and failure cleanup:

```kotlin
private enum class EngineState { INITIALIZING, READY, FAILED, CLOSED }
private var engineState = EngineState.INITIALIZING

private fun initializeRtc() {
    try {
        require(hasRequiredPermissions(appContext)) { "缺少 RECORD_AUDIO 运行时权限" }
        initFactory()
        createPeerConnection()
        createLocalAudioTrack()
        engineState = EngineState.READY
    } catch (t: Throwable) {
        engineState = EngineState.FAILED
        disposeRtcResources()
        postMain { onError(t) }
    }
}

private fun requireReady() {
    check(engineState == EngineState.READY) { "WebRTC engine state=$engineState" }
}

private fun disposeRtcResources() {
    peerConnection?.dispose()
    localAudioTrack?.dispose()
    audioSource?.dispose()
    factory?.dispose()
    audioDeviceModule?.release()
    peerConnection = null
    localAudioTrack = null
    audioSource = null
    factory = null
    audioDeviceModule = null
}
```

The constructor queues `initializeRtc()` first. Offer, answer, and candidate jobs call `requireReady()`. `close()` sets `CLOSED` on the RTC executor and calls `disposeRtcResources()`.

Every `SdpObserver` and `PeerConnection.Observer` method that reads or writes `remoteDescriptionSet`, `pendingRemoteCandidates`, peer connection state, SDP, ICE, or remote tracks first enters:

```kotlin
override fun onSetSuccess() {
    runRtc {
        if (engineState != EngineState.READY) return@runRtc
        remoteDescriptionSet = true
        pendingRemoteCandidates.forEach { candidate ->
            check(peerConnectionOrThrow().addIceCandidate(candidate))
        }
        pendingRemoteCandidates.clear()
        onSet()
    }
}
```

Apply the same executor hop to SDP creation, ICE, connection state, and remote-track callbacks. Replace all direct `mainHandler.post` calls with `postMain`.

Remove `AudioManager`, `oldMode`, `oldSpeakerphone`, `configureAndroidAudio()`, and `restoreAndroidAudio()` from `RiderAudioEngine`; Task 7 makes routing the single owner.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.VoxGateTest"
.\gradlew.bat :app:assembleDebug
rg "AudioManager|configureAndroidAudio|restoreAndroidAudio" app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt
git diff --check
```

Expected: tests/build pass; `rg` has no matches.

```powershell
git add app/src/main/java/com/kuma/motointercom/VoxGate.kt app/src/main/java/com/kuma/motointercom/RiderAudioEngine.kt app/src/main/java/com/kuma/motointercom/IntercomManager.kt app/src/main/java/com/kuma/motointercom/IntercomService.kt app/src/test/java/com/kuma/motointercom/VoxGateTest.kt
git commit -m "refactor: isolate VOX and serialize WebRTC callbacks"
```

---

### Task 7: Make AudioRouteController the Only AudioManager Owner

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/AudioRouteController.kt`

**Interfaces:**

- Consumes: API 31+ `AudioManager.OnCommunicationDeviceChangedListener`.
- Produces: serialized route/restore operations on one process-wide executor.

- [ ] **Step 1: Record the platform-only acceptance assertions**

Before editing, save this exact checklist in the task notes:

```text
1. Starting intercom sets MODE_IN_COMMUNICATION.
2. Xiaomi 13 selects OPPO Enco X3 as communicationDevice.
3. Stopping clears or restores the original communicationDevice.
4. Stopping restores the original AudioManager.mode.
5. A queued callback after close cannot publish SCO-connected state.
```

These assertions use device evidence because local JVM tests cannot execute Android `AudioManager`.

- [ ] **Step 2: Create the API 31+ implementation**

```kotlin
package com.kuma.motointercom

import android.annotation.TargetApi
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.io.Closeable
import java.util.concurrent.Executor

@TargetApi(Build.VERSION_CODES.S)
internal class ModernAudioRoute(
    private val audioManager: AudioManager,
    private val callbackExecutor: Executor,
    private val onBluetoothConnected: (String) -> Unit,
    private val onDeviceLost: () -> Unit
) : Closeable {
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (isBluetooth(device)) {
            onBluetoothConnected(device?.productName?.toString().orEmpty())
        } else {
            onDeviceLost()
        }
    }
    private var registered = false

    fun register() {
        if (registered) return
        audioManager.addOnCommunicationDeviceChangedListener(callbackExecutor, listener)
        registered = true
    }

    fun route(): Boolean {
        val target = audioManager.availableCommunicationDevices
            .filter(::isBluetooth)
            .minByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 0 else 1 }
            ?: return false
        return audioManager.setCommunicationDevice(target)
    }

    fun currentName(): String? =
        audioManager.communicationDevice?.takeIf(::isBluetooth)?.productName?.toString()

    override fun close() {
        if (registered) {
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
            registered = false
        }
        audioManager.clearCommunicationDevice()
    }

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean =
        device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
}
```

- [ ] **Step 3: Simplify AudioRouteController ownership**

Record initial state at construction:

```kotlin
private val initialMode = audioManager.mode
@Suppress("DEPRECATION")
private val initialSpeakerphoneOn = audioManager.isSpeakerphoneOn
private var modernRoute: ModernAudioRoute? = null
```

Use one process-wide executor so an old restore always executes before a new route:

```kotlin
companion object {
    private val ROUTE_EXECUTOR = Executors.newSingleThreadExecutor()
}
```

Do not shut this executor down per controller. For API 31+, instantiate `ModernAudioRoute` only inside the SDK guard. Replace the Proxy/reflection registration, dynamic listener field, modern device-selection helpers, and delayed reflection cleanup with the new class. All callbacks post through:

```kotlin
private fun postMain(block: () -> Unit) {
    mainHandler.post {
        if (!closed.get()) block()
    }
}
```

`reset()` sets `closed` first, queues `modernRoute?.close()` or legacy SCO stop, restores `initialSpeakerphoneOn` and `initialMode`, then unregisters receivers/callbacks. It never writes unconditional `MODE_NORMAL`.

- [ ] **Step 4: Fix API-level diagnostics**

Keep `AudioDeviceInfo.address` access only inside `ModernAudioRoute` or a method guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`. Keep `TYPE_BLE_HEADSET` only inside API 31+ code. No suppression may replace a missing runtime guard.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/src/main/java/com/kuma/motointercom/ModernAudioRoute.kt app/src/main/java/com/kuma/motointercom/AudioRouteController.kt
git commit -m "fix: make audio routing single-owner"
```

Expected: build succeeds and no dynamic Proxy remains.

---

### Task 8: Separate Permissions, Main Screen, and Activity Binding

**Files:**

- Create: `app/src/main/java/com/kuma/motointercom/PermissionPolicy.kt`
- Create: `app/src/main/java/com/kuma/motointercom/BoundedLogBuffer.kt`
- Create: `app/src/main/java/com/kuma/motointercom/MainScreen.kt`
- Create: `app/src/main/java/com/kuma/motointercom/RippleView.kt`
- Create: `app/src/main/java/com/kuma/motointercom/VisualizerView.kt`
- Create: `app/src/test/java/com/kuma/motointercom/PermissionPolicyTest.kt`
- Create: `app/src/test/java/com/kuma/motointercom/BoundedLogBufferTest.kt`
- Modify: `app/src/main/java/com/kuma/motointercom/MainActivity.kt`

**Interfaces:**

- Consumes: top-level `LanRiderDevice` from Task 5.
- Produces: `PermissionPolicy`, `MainScreen`, and lifecycle-safe binding.

- [ ] **Step 1: Write failing permission and log tests**

```kotlin
package com.kuma.motointercom

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun api32NeedsAudioLocationAndBluetooth() {
        assertEquals(
            setOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            PermissionPolicy.corePermissions(32).toSet()
        )
    }

    @Test
    fun notificationDenialDoesNotBlockApi33Core() {
        assertFalse(PermissionPolicy.corePermissions(33).contains(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            PermissionPolicy.optionalPermissions(33)
        )
        assertTrue(PermissionPolicy.canStart(33) { it != Manifest.permission.POST_NOTIFICATIONS })
    }
}
```

```kotlin
package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedLogBufferTest {
    @Test
    fun keepsNewestThreeEntries() {
        val log = BoundedLogBuffer(3)
        listOf("1", "2", "3", "4").forEach(log::append)
        assertEquals(listOf("2", "3", "4"), log.snapshot())
    }
}
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.PermissionPolicyTest" --tests "com.kuma.motointercom.BoundedLogBufferTest"
```

Expected: compilation fails because both production types are missing.

- [ ] **Step 3: Implement the pure policies**

```kotlin
package com.kuma.motointercom

import android.Manifest

internal object PermissionPolicy {
    fun corePermissions(apiLevel: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (apiLevel >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            if (apiLevel >= 31) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (apiLevel >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun optionalPermissions(apiLevel: Int): List<String> =
        if (apiLevel >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun canStart(apiLevel: Int, granted: (String) -> Boolean): Boolean =
        corePermissions(apiLevel).all(granted)
}
```

```kotlin
package com.kuma.motointercom

internal class BoundedLogBuffer(private val limit: Int = 300) {
    private val lines = ArrayDeque<String>()

    init {
        require(limit > 0)
    }

    fun append(value: String) {
        if (lines.size == limit) lines.removeFirst()
        lines.addLast(value)
    }

    fun snapshot(): List<String> = lines.toList()
}
```

- [ ] **Step 4: Run GREEN**

Run the Step 2 command.

Expected: both test classes pass.

- [ ] **Step 5: Extract the screen and custom views**

`MainScreen` exposes exactly:

```kotlin
internal class MainScreen(
    private val activity: Activity,
    initialRiderName: String,
    private val onToggleIntercom: () -> Unit,
    private val onConnectDevice: (LanRiderDevice) -> Unit
) {
    val root: View
    val riderName: String
    fun setRunning(running: Boolean, canStart: Boolean)
    fun setStatus(message: String)
    fun setAudioSource(status: String, bluetooth: Boolean)
    fun setRemoteRider(name: String?)
    fun setLanDevices(devices: List<LanRiderDevice>)
    fun setAudioLevel(level: Float)
    fun appendLog(message: String)
    fun stopAnimations()
}
```

Move these exact UI symbols from `MainActivity` into this class: `buildSimpleUi`, `buildHeader`, `buildConnectionCard`, `buildMainButton`, `buildAudioCard`, `buildVoxCard`, `buildDiscoveryCard`, `buildSettingsCard`, `buildLogCard`, `setIntercomRunning`, `setStatus`, `appendLog`, `updateAudioSource`, `renderLanDevices`, `deviceRow`, `updateVoxDisplay`, `updateActionButton`, `updateMotionForStatus`, `targetButtonColor`, `animateButtonColor`, `applyButtonColor`, `statusColor`, `statusDetail`, `isPairingStatus`, `isConnectedStatus`, and all `create*`/layout helpers. `appendLog` writes to `BoundedLogBuffer(300)` and sets the TextView from `snapshot().joinToString("\n")`. Move the existing nested animation classes unchanged into `RippleView.kt` and `VisualizerView.kt`, adding a public `stop()` that cancels callbacks/animators. `MainScreen.stopAnimations()` calls both.

`MainActivity` keeps only permission flow, service connection, Activity lifecycle, persisted rider name, and callback forwarding to `MainScreen`.

In `MainScreen.deviceRow()` keep every connect target at least 48 dp high:

```kotlin
button.minHeight = dp(48)
button.minimumHeight = dp(48)
```

In the pill renderer expose non-color state:

```kotlin
pill.isSelected = active
pill.contentDescription = "$label: ${if (active) "active" else "inactive"}"
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    pill.stateDescription = if (active) "active" else "inactive"
}
```

- [ ] **Step 6: Make binding follow onStart/onStop**

Use:

```kotlin
private var bindingRegistered = false
private var serviceConnected = false

override fun onStart() {
    super.onStart()
    bindIntercomService(flags = 0)
}

override fun onStop() {
    intercomService?.setListener(null)
    if (bindingRegistered) unbindService(serviceConnection)
    bindingRegistered = false
    serviceConnected = false
    intercomService = null
    screen.stopAnimations()
    super.onStop()
}

private fun bindIntercomService(
    intent: Intent = Intent(this, IntercomService::class.java),
    flags: Int
) {
    if (bindingRegistered) return
    bindingRegistered = bindService(intent, serviceConnection, flags)
}
```

`onServiceConnected` sets `serviceConnected = true` and registers the listener. Starting intercom calls `startForegroundService`, then `bindIntercomService(intent, Context.BIND_AUTO_CREATE)`. A failed flags-0 bind is allowed when no started service exists.

Use `PermissionPolicy.corePermissions` for the hard start gate. Request `optionalPermissions` separately; refusal only appends a log line and never disables the start button.

- [ ] **Step 7: Verify and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kuma.motointercom.PermissionPolicyTest" --tests "com.kuma.motointercom.BoundedLogBufferTest"
.\gradlew.bat :app:assembleDebug
git diff --check
git add app/src/main/java/com/kuma/motointercom/PermissionPolicy.kt app/src/main/java/com/kuma/motointercom/BoundedLogBuffer.kt app/src/main/java/com/kuma/motointercom/MainScreen.kt app/src/main/java/com/kuma/motointercom/RippleView.kt app/src/main/java/com/kuma/motointercom/VisualizerView.kt app/src/main/java/com/kuma/motointercom/MainActivity.kt app/src/test/java/com/kuma/motointercom/PermissionPolicyTest.kt app/src/test/java/com/kuma/motointercom/BoundedLogBufferTest.kt
git commit -m "refactor: extract lifecycle-safe main screen"
```

---

### Task 9: Clear Lint, Build the Device Script, and Run Acceptance

**Files:**

- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values-v31/styles.xml`
- Move: `app/src/main/res/drawable/splash_bg.png` to `app/src/main/res/drawable-nodpi/splash_bg.png`
- Create: `scripts/verify-device-matrix.ps1`
- Local-only modify: `local.properties`

**Interfaces:**

- Consumes: debug APK and connected MI 6 `9688fa60` plus Xiaomi 13 `efcb9031`.
- Produces: saved logs under `build/device-verification/<timestamp>/` and a nonzero exit code on machine-check failure.

- [ ] **Step 1: Fix manifest, theme, and resource placement**

Add:

```xml
<uses-permission
    android:name="android.permission.ACCESS_COARSE_LOCATION"
    android:maxSdkVersion="32" />
```

Change Wi-Fi Direct capability to:

```xml
<uses-feature
    android:name="android.hardware.wifi.direct"
    android:required="false" />
```

Make API 31+ `AppTheme` match the base non-fullscreen theme:

```xml
<style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:windowNoTitle">true</item>
    <item name="android:windowActionBar">false</item>
    <item name="android:windowFullscreen">false</item>
    <item name="android:windowBackground">@color/motocom_background</item>
    <item name="android:colorAccent">@color/motocom_accent_green</item>
</style>
```

Keep all `windowSplashScreen*` attributes only in `SplashTheme`. Move the bitmap to `drawable-nodpi` without changing pixels. Remove only color resources still reported unused after Task 8.

On this machine set the ignored local file to:

```properties
sdk.dir=C\:/Users/kuma/AppData/Local/Android/Sdk
```

- [ ] **Step 2: Run full automatic verification**

```powershell
$env:JAVA_HOME="F:\Android\jbr"
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug --console=plain
git diff --check
```

Expected: Gradle exit 0; unit tests execute rather than `NO-SOURCE`; Lint reports zero errors.

- [ ] **Step 3: Create the device evidence script**

Create the complete script:

```powershell
param(
    [string[]]$Serials = @("9688fa60", "efcb9031"),
    [string]$Adb = "C:\Users\kuma\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path "build\device-verification" $stamp
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Invoke-AdbCapture {
    param(
        [string]$Serial,
        [string]$Name,
        [string[]]$CommandArgs
    )

    $path = Join-Path $out "$Serial-$Name.txt"
    $output = & $Adb -s $Serial @CommandArgs 2>&1
    $output | Set-Content -Encoding utf8 -LiteralPath $path
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: $Serial $Name"
    }
}

foreach ($serial in $Serials) {
    $state = (& $Adb -s $serial get-state 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $state -ne "device") {
        throw "ADB device not ready: $serial state=$state"
    }

    Invoke-AdbCapture $serial "install" @("install", "-r", $Apk)
    Invoke-AdbCapture $serial "clear-logcat" @("logcat", "-c")
    Invoke-AdbCapture $serial "force-stop" @(
        "shell", "am", "force-stop", "com.kuma.motointercom"
    )
    Invoke-AdbCapture $serial "launch" @(
        "shell", "monkey", "-p", "com.kuma.motointercom", "1"
    )
    Start-Sleep -Seconds 2
    Invoke-AdbCapture $serial "service" @(
        "shell", "dumpsys", "activity", "services", "com.kuma.motointercom"
    )
    Invoke-AdbCapture $serial "audio" @("shell", "dumpsys", "audio")
    Invoke-AdbCapture $serial "ui-dump" @(
        "shell", "uiautomator", "dump", "/sdcard/motocom.xml"
    )

    $uiPath = Join-Path $out "$serial-ui.xml"
    $pullOutput = & $Adb -s $serial pull "/sdcard/motocom.xml" $uiPath 2>&1
    $pullOutput | Set-Content -Encoding utf8 -LiteralPath (
        Join-Path $out "$serial-ui-pull.txt"
    )
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: $serial ui-pull"
    }

    Invoke-AdbCapture $serial "motocom-logcat" @(
        "logcat", "-d", "-s",
        "MotoComP2P", "IntercomSignal", "RiderAudioEngine", "AudioRouteController"
    )
}

Write-Output "Device evidence: $((Resolve-Path $out).Path)"
```

The script fails immediately on a machine-check error. This run passes both `9688fa60` and `efcb9031`.

- [ ] **Step 4: Run the connected-device evidence pass**

Confirm both devices are `device`:

```powershell
& "C:\Users\kuma\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices -l
```

Then run:

```powershell
.\scripts\verify-device-matrix.ps1
```

- [ ] **Step 5: Execute objective two-device and Bluetooth checks**

Perform in order:

1. Install and launch on MI 6 and Xiaomi 13; grant required runtime permissions.
2. Start intercom through both rendered UIs; confirm both foreground services and capture P2P/signaling state.
3. Confirm Xiaomi 13 selects the connected Bluetooth headset as communication device using `dumpsys audio` and app logs.
4. Background and reopen both Activities; confirm each UI reconnects to its running service without a crash.
5. Stop and immediately restart once; confirm logs contain no stale-socket resurrection or rejected-executor crash.
6. Stop again on both devices; confirm services are absent and audio routing is released.
7. Record human listening/speaking quality as `NOT RUN` until the user supplies subjective confirmation.

Record pass/fail/not-run beside each item in `build/device-verification/<timestamp>/acceptance.txt`. Any failed objective device item blocks the final commit; only unconfirmed human listening items may remain not run.

- [ ] **Step 6: Commit the verified platform cleanup**

Only after Steps 2–5 pass:

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/res/values-v31/styles.xml app/src/main/res/drawable-nodpi/splash_bg.png scripts/verify-device-matrix.ps1
git add -u app/src/main/res/drawable/splash_bg.png
git commit -m "fix: align platform config and device checks"
git status --short --branch
```

Expected: branch is clean and ahead of origin only by the reviewed local commits. Do not push.

## Final Review Gate

After Task 9:

```powershell
$env:JAVA_HOME="F:\Android\jbr"
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug --console=plain
git diff --check
git log --oneline origin/ui/light-theme-redesign..HEAD
git status --short --branch
```

Review the complete diff from `f00a4a5` to `HEAD` for:

- stale-session checks before and after resource handoff;
- socket ownership and closure on every failure path;
- one owner for `AudioManager`;
- no UI listener retained after `onStop()`;
- no protocol input without an explicit bound;
- no unrelated formatting or dependency additions;
- no push or PR.
