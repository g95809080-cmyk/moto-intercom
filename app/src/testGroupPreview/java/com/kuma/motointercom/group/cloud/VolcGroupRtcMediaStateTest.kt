package com.kuma.motointercom.group.cloud

import com.ss.bytertc.engine.data.StreamInfo
import com.ss.bytertc.engine.utils.IAudioFrame
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcGroupRtcMediaStateTest {
    @Test
    fun roomStateZeroIsJoinedAndNonZeroIsTheSdkErrorCode() {
        assertEquals(GroupRtcEvent.Joined, volcRoomStateEvent(0))

        val publishLimit = volcRoomStateEvent(-1080) as GroupRtcEvent.JoinFailed
        assertEquals(-1080, publishLimit.code)
        assertTrue(publishLimit.message.contains("音频选路"))

        val subscribeLimit = volcRoomStateEvent(-1070) as GroupRtcEvent.JoinFailed
        assertEquals(-1070, subscribeLimit.code)
        assertTrue(subscribeLimit.message.contains("音频选路"))
    }

    @Test
    fun compatTokenRejectionBeforeTypedCallbackStillInvalidatesTheCachedToken() {
        for (code in listOf(-1010, -1009)) {
            val gate = VolcRoomEventGate()
            assertTrue(gate.accept(GroupRtcEvent.Joined))
            val first = volcRoomStateEvent(code) as GroupRtcEvent.JoinFailed
            assertTrue(gate.accept(first))
            assertEquals(-1000, first.code)
            assertFalse(gate.accept(GroupRtcEvent.JoinFailed(-1000, "typed")))
        }
    }

    @Test
    fun remoteMuteTrackerMapsUsersToV360StreamIdsAcrossNormalAndAudioRoutingCallbacks() {
        val tracker = VolcRemoteAudioMuteTracker()

        val changes = mutableListOf<Pair<String, Boolean>>()
        fun allow(vararg users: String) = tracker.setAllowedUsers(users.toSet()) { stream, muted -> changes += stream to muted }
        allow("peer-b")
        assertEquals(
            "room_peer-a_0",
            tracker.onRemoteStreamObserved("peer-a", "room_peer-a_0", published = true)
        )
        assertNull(tracker.onRemoteStreamObserved("peer-a", "room_peer-a_0", published = true))
        assertEquals(setOf("room_peer-a_0"), tracker.mutedStreamIds())

        assertNull(tracker.onRemoteStreamObserved("peer-b", "room_peer-b_0", published = true))
        allow()
        assertEquals(listOf("room_peer-b_0" to true), changes)
        assertEquals(setOf("room_peer-a_0", "room_peer-b_0"), tracker.mutedStreamIds())

        allow("peer-a")
        assertEquals("room_peer-a_0" to false, changes.last())
        assertEquals(setOf("room_peer-b_0"), tracker.mutedStreamIds())

        tracker.onUserLeft("peer-b")
        assertEquals(emptySet<String>(), tracker.mutedStreamIds())
    }

    @Test
    fun mutedSeatIsSilentFromItsFirstNativeFrameBeforeStreamDiscovery() {
        val tracker = VolcRemoteAudioMuteTracker()
        tracker.setAllowedUsers(setOf("seat-2")) { _, _ -> }
        val buffer = ByteBuffer.allocateDirect(19)
        val frame = object : IAudioFrame {
            override fun getDataBuffer() = buffer
            override fun data_size() = buffer.capacity()
            override fun timestamp_us() = 0L
            override fun sample_rate() = com.ss.bytertc.engine.data.AudioSampleRate.AUDIO_SAMPLE_RATE_48000
            override fun channel() = com.ss.bytertc.engine.data.AudioChannel.AUDIO_CHANNEL_MONO
            override fun frame_type(): com.ss.bytertc.engine.data.AudioFrameType? = null
            override fun getExtraInfo(): ByteBuffer? = null
            override fun extraInfoSize() = 0
            override fun release() = Unit
        }
        fun assertFrame(id: String, expected: Int) {
            for (index in 0 until buffer.capacity()) buffer.put(index, 7)
            val info = StreamInfo("stream", "room", id, 0, false).apply { userId = id }
            assertEquals(0, tracker.onProcessRemoteUserAudioFrame("unseen", info, frame))
            assertTrue((0 until buffer.capacity()).all { buffer.get(it).toInt() == expected })
        }
        assertFrame("seat-1", 0)
        assertFrame("seat-2", 7)
        assertFrame("", 0)
        assertFrame("readmitted-before-roster", 0)
        tracker.setAllowedUsers(setOf("seat-2", "new-allowed-peer")) { _, _ -> }
        assertFrame("readmitted-before-roster", 0)
        assertFrame("new-allowed-peer", 7)
    }

    @Test
    fun roomEventGateDeduplicatesTypedAndCompatCallbacksPerAttempt() {
        val gate = VolcRoomEventGate()

        assertTrue(gate.accept(GroupRtcEvent.JoinFailed(-1000, "typed")))
        assertFalse(gate.accept(GroupRtcEvent.JoinFailed(-1000, "compat")))
        assertFalse(gate.accept(GroupRtcEvent.ConnectionLost("leave")))
        assertFalse(gate.accept(GroupRtcEvent.Joined))
        assertFalse(gate.accept(GroupRtcEvent.ActiveSpeakers(setOf("peer-a"))))
        assertFalse(gate.accept(GroupRtcEvent.Error("late")))
        assertFalse(gate.accept(GroupRtcEvent.TokenWillExpire))
        assertTrue(gate.isTerminal())

        gate.reset()
        assertFalse(gate.isTerminal())
        assertTrue(gate.accept(GroupRtcEvent.Joined))
        assertFalse(gate.accept(GroupRtcEvent.Joined))
        assertTrue(gate.accept(GroupRtcEvent.ConnectionLost("lost")))
        assertFalse(gate.accept(GroupRtcEvent.ConnectionLost("compat-lost")))
        assertFalse(gate.accept(GroupRtcEvent.Joined))
        assertTrue(gate.isTerminal())
    }

    @Test
    fun releaseVolcRtcResourcesExecutesAllStepsAndRethrowsFailure() {
        val executed = mutableListOf<String>()
        val error = assertThrows(IllegalStateException::class.java) {
            releaseVolcRtcResources(
                leaveRoom = {
                    executed += "leaveRoom"
                    -1001
                },
                destroyRoom = {
                    executed += "destroyRoom"
                    throw IllegalStateException("room destroy boom")
                },
                stopAudioCapture = {
                    executed += "stopAudioCapture"
                },
                destroyEngine = {
                    executed += "destroyEngine"
                    throw IllegalStateException("engine destroy boom")
                },
                closeAudioFocus = {
                    executed += "closeAudioFocus"
                },
                expectLeaveRoomSuccess = true
            )
        }
        assertEquals(
            listOf("leaveRoom", "destroyRoom", "stopAudioCapture", "destroyEngine", "closeAudioFocus"),
            executed
        )
        assertTrue(error.message!!.contains("leaveRoom failed with code -1001"))
        assertEquals(2, error.suppressed.size)
    }
}
