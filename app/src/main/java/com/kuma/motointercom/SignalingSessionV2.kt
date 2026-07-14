package com.kuma.motointercom

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal class SignalingSessionV2 private constructor(
    val channel: PendingControlChannel,
    val pinnedIdentity: PinnedChannelIdentity,
    val peer: PeerIdentity,
    val originatingAttempt: ConnectionAttempt?,
    private val socket: Socket,
    private val phaseMachine: SignalingPhaseMachine
) : Closeable {
    private val closed = AtomicBoolean(false)

    val requestRole: RequestRole
        get() = requireNotNull(channel.requestRole)

    val wireRequestKey: WireRequestKey
        get() = pinnedIdentity.wireRequestKey

    val targetLock: TargetLock
        get() = TargetLock(
            targetDeviceId = pinnedIdentity.remoteDeviceId.value,
            expectedRemoteSessionId = pinnedIdentity.remoteSessionId
        )

    val phase: SignalingPhase
        get() = phaseMachine.phase

    val isClosed: Boolean
        get() = closed.get() || socket.isClosed

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        phaseMachine.close()
        runCatching { socket.close() }
    }

    companion object {
        private const val HELLO_READ_TIMEOUT_MS = 1_000

        fun establish(
            socket: Socket,
            transport: Transport,
            physicalRole: PhysicalSocketRole,
            openedAtElapsedMs: Long,
            localDeviceId: String,
            localRuntimeSessionId: RuntimeSessionId,
            localNickname: String,
            localDeviceName: String,
            originatingAttempt: ConnectionAttempt?,
            expectedRemoteTargetLock: TargetLock? = originatingAttempt?.targetLock
        ): SignalingSessionV2 {
            val previousTimeout = socket.soTimeout
            try {
                val pendingChannel = PendingControlChannel(
                    channelId = ControlChannelId.create(),
                    transport = transport,
                    physicalRole = physicalRole,
                    requestRole = originatingAttempt?.let { RequestRole.REQUESTER },
                    openedAtElapsedMs = openedAtElapsedMs
                )
                if (!socket.isConnected || socket.isClosed) {
                    throw SignalingV2Exception("control channel Socket is not connected")
                }
                if (
                    originatingAttempt != null &&
                    expectedRemoteTargetLock != originatingAttempt.targetLock
                ) {
                    throw SignalingV2Exception("outbound attempt TargetLock changed before HELLO")
                }
                if (
                    originatingAttempt != null &&
                    originatingAttempt.runtimeSessionId != localRuntimeSessionId
                ) {
                    throw SignalingV2Exception("outbound attempt runtime does not match local HELLO")
                }

                val localDevice = DeviceId.parse(localDeviceId)
                requireCanonicalUuid(localRuntimeSessionId.value, "localSessionId")
                socket.soTimeout = HELLO_READ_TIMEOUT_MS
                val codec = SignalingV2Codec()
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val phaseMachine = SignalingPhaseMachine(
                    originatingAttempt?.let { RequestRole.REQUESTER }
                )
                val result = if (originatingAttempt == null) {
                    exchangeAsResponder(
                        codec = codec,
                        input = input,
                        output = output,
                        phaseMachine = phaseMachine,
                        localDevice = localDevice,
                        localSession = localRuntimeSessionId,
                        localNickname = localNickname,
                        localDeviceName = localDeviceName,
                        expectedRemoteTargetLock = expectedRemoteTargetLock
                    )
                } else {
                    exchangeAsRequester(
                        codec = codec,
                        input = input,
                        output = output,
                        phaseMachine = phaseMachine,
                        localDevice = localDevice,
                        localSession = localRuntimeSessionId,
                        localNickname = localNickname,
                        localDeviceName = localDeviceName,
                        attempt = originatingAttempt
                    )
                }

                return SignalingSessionV2(
                    channel = pendingChannel.copy(requestRole = result.requestRole),
                    pinnedIdentity = result.pinnedIdentity,
                    peer = result.toVerifiedPeer(),
                    originatingAttempt = originatingAttempt,
                    socket = socket,
                    phaseMachine = phaseMachine
                )
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw when (t) {
                    is SignalingV2Exception -> t
                    else -> SignalingV2Exception("HELLO exchange failed", t)
                }
            } finally {
                if (!socket.isClosed) runCatching { socket.soTimeout = previousTimeout }
            }
        }

        private fun exchangeAsRequester(
            codec: SignalingV2Codec,
            input: DataInputStream,
            output: DataOutputStream,
            phaseMachine: SignalingPhaseMachine,
            localDevice: DeviceId,
            localSession: RuntimeSessionId,
            localNickname: String,
            localDeviceName: String,
            attempt: ConnectionAttempt
        ): HelloExchangeResult {
            val expectedRemoteDevice = DeviceId.parse(attempt.targetLock.targetDeviceId)
            val localKey = WireRequestKey(
                requesterDeviceId = localDevice,
                requesterSessionId = localSession,
                attemptId = attempt.id,
                responderDeviceId = expectedRemoteDevice
            )
            val localPinned = PinnedChannelIdentity(
                localDeviceId = localDevice,
                localSessionId = localSession,
                remoteDeviceId = expectedRemoteDevice,
                remoteSessionId = attempt.targetLock.expectedRemoteSessionId,
                wireRequestKey = localKey
            )
            val requesterHello = helloEnvelope(
                requestRole = RequestRole.REQUESTER,
                attemptId = attempt.id,
                localDevice = localDevice,
                targetDevice = expectedRemoteDevice,
                localSession = localSession,
                nickname = localNickname,
                deviceName = localDeviceName
            )
            localPinned.requireOutgoing(requesterHello)
            writeHello(codec, output, phaseMachine, requesterHello)

            val firstRemote = readHello(codec, input)
            requireRemoteEndpoint(
                firstRemote.envelope,
                localDevice,
                attempt.targetLock
            )
            return when (firstRemote.message.requestRole) {
                RequestRole.RESPONDER -> {
                    localPinned.requireIncoming(firstRemote.envelope)
                    phaseMachine.onFrame(FrameDirection.INBOUND, firstRemote.message)
                    HelloExchangeResult(RequestRole.REQUESTER, localPinned, firstRemote.message)
                }
                RequestRole.REQUESTER -> resolveGlare(
                    codec = codec,
                    input = input,
                    output = output,
                    phaseMachine = phaseMachine,
                    localDevice = localDevice,
                    localSession = localSession,
                    localNickname = localNickname,
                    localDeviceName = localDeviceName,
                    localKey = localKey,
                    localPinned = localPinned,
                    remoteRequester = firstRemote
                )
            }
        }

        private fun resolveGlare(
            codec: SignalingV2Codec,
            input: DataInputStream,
            output: DataOutputStream,
            phaseMachine: SignalingPhaseMachine,
            localDevice: DeviceId,
            localSession: RuntimeSessionId,
            localNickname: String,
            localDeviceName: String,
            localKey: WireRequestKey,
            localPinned: PinnedChannelIdentity,
            remoteRequester: DecodedHello
        ): HelloExchangeResult {
            phaseMachine.onFrame(FrameDirection.INBOUND, remoteRequester.message)
            val remoteKey = remoteRequester.envelope.requesterKey()
            if (localKey == remoteKey) throw SignalingV2Exception("duplicate requester identity")
            val localWins = localKey < remoteKey
            phaseMachine.resolveGlare(localWins)
            if (localWins) {
                val response = readHello(codec, input)
                if (response.message.requestRole != RequestRole.RESPONDER) {
                    throw SignalingV2Exception("expected responder HELLO after glare")
                }
                localPinned.requireIncoming(response.envelope)
                phaseMachine.onFrame(FrameDirection.INBOUND, response.message)
                return HelloExchangeResult(RequestRole.REQUESTER, localPinned, response.message)
            }

            val remotePinned = PinnedChannelIdentity(
                localDeviceId = localDevice,
                localSessionId = localSession,
                remoteDeviceId = remoteRequester.envelope.sourceDeviceId,
                remoteSessionId = remoteRequester.envelope.sourceSessionId,
                wireRequestKey = remoteKey
            )
            remotePinned.requireIncoming(remoteRequester.envelope)
            val responderHello = helloEnvelope(
                requestRole = RequestRole.RESPONDER,
                attemptId = remoteKey.attemptId,
                localDevice = localDevice,
                targetDevice = remoteKey.requesterDeviceId,
                localSession = localSession,
                nickname = localNickname,
                deviceName = localDeviceName
            )
            remotePinned.requireOutgoing(responderHello)
            writeHello(codec, output, phaseMachine, responderHello)
            return HelloExchangeResult(
                RequestRole.RESPONDER,
                remotePinned,
                remoteRequester.message
            )
        }

        private fun exchangeAsResponder(
            codec: SignalingV2Codec,
            input: DataInputStream,
            output: DataOutputStream,
            phaseMachine: SignalingPhaseMachine,
            localDevice: DeviceId,
            localSession: RuntimeSessionId,
            localNickname: String,
            localDeviceName: String,
            expectedRemoteTargetLock: TargetLock?
        ): HelloExchangeResult {
            val requester = readHello(codec, input)
            if (requester.message.requestRole != RequestRole.REQUESTER) {
                throw SignalingV2Exception("first inbound HELLO must be requester")
            }
            requireRemoteEndpoint(requester.envelope, localDevice, expectedRemoteTargetLock)
            phaseMachine.onFrame(FrameDirection.INBOUND, requester.message)
            val requestKey = requester.envelope.requesterKey()
            val pinned = PinnedChannelIdentity(
                localDeviceId = localDevice,
                localSessionId = localSession,
                remoteDeviceId = requester.envelope.sourceDeviceId,
                remoteSessionId = requester.envelope.sourceSessionId,
                wireRequestKey = requestKey
            )
            pinned.requireIncoming(requester.envelope)
            val responderHello = helloEnvelope(
                requestRole = RequestRole.RESPONDER,
                attemptId = requestKey.attemptId,
                localDevice = localDevice,
                targetDevice = requestKey.requesterDeviceId,
                localSession = localSession,
                nickname = localNickname,
                deviceName = localDeviceName
            )
            pinned.requireOutgoing(responderHello)
            writeHello(codec, output, phaseMachine, responderHello)
            return HelloExchangeResult(RequestRole.RESPONDER, pinned, requester.message)
        }

        private fun writeHello(
            codec: SignalingV2Codec,
            output: DataOutputStream,
            phaseMachine: SignalingPhaseMachine,
            envelope: SignalingEnvelopeV2
        ) {
            SignalingV2Framing.write(output, codec.encode(envelope))
            phaseMachine.onFrame(FrameDirection.OUTBOUND, envelope.message)
        }

        private fun readHello(codec: SignalingV2Codec, input: DataInputStream): DecodedHello {
            val envelope = codec.decode(SignalingV2Framing.read(input))
            val message = envelope.message as? SignalingMessageV2.Hello
                ?: throw SignalingV2Exception("expected HELLO frame")
            return DecodedHello(envelope, message)
        }

        private fun requireRemoteEndpoint(
            envelope: SignalingEnvelopeV2,
            localDevice: DeviceId,
            expectedRemoteTargetLock: TargetLock?
        ) {
            if (envelope.targetDeviceId != localDevice) {
                throw SignalingV2Exception("HELLO target does not match local device")
            }
            if (expectedRemoteTargetLock == null) return
            if (
                envelope.sourceDeviceId.value != expectedRemoteTargetLock.targetDeviceId ||
                envelope.sourceSessionId != expectedRemoteTargetLock.expectedRemoteSessionId
            ) {
                throw SignalingV2Exception("HELLO source does not match TargetLock")
            }
        }

        private fun helloEnvelope(
            requestRole: RequestRole,
            attemptId: ConnectionAttemptId,
            localDevice: DeviceId,
            targetDevice: DeviceId,
            localSession: RuntimeSessionId,
            nickname: String,
            deviceName: String
        ) = SignalingEnvelopeV2(
            attemptId = attemptId,
            sourceDeviceId = localDevice,
            targetDeviceId = targetDevice,
            sourceSessionId = localSession,
            message = SignalingMessageV2.Hello(
                requestRole = requestRole,
                nickname = nickname,
                deviceName = deviceName,
                capabilities = emptySet()
            )
        )

        private fun HelloExchangeResult.toVerifiedPeer(): PeerIdentity = PeerIdentity(
            deviceId = pinnedIdentity.remoteDeviceId.value,
            nickname = remoteHello.nickname,
            deviceName = remoteHello.deviceName,
            runtimeSessionId = pinnedIdentity.remoteSessionId,
            isDeviceIdVerified = true
        )

        private data class DecodedHello(
            val envelope: SignalingEnvelopeV2,
            val message: SignalingMessageV2.Hello
        )

        private data class HelloExchangeResult(
            val requestRole: RequestRole,
            val pinnedIdentity: PinnedChannelIdentity,
            val remoteHello: SignalingMessageV2.Hello
        )
    }
}
