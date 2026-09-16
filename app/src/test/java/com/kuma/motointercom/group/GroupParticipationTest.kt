package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test

class GroupParticipationTest {
    private val room = GroupRoomKey("room", "host-runtime")
    @Test fun freshProcessNeverRestoresParticipation() {
        val old = GroupParticipation().begin(room)
        val fresh = GroupParticipation()
        assertNull(fresh.token)
        assertFalse(fresh.isCurrent(requireNotNull(old.token)))
        assertFalse(fresh.begin(room).isCurrent(requireNotNull(old.token)))
    }
    @Test fun stopClearsMuteStateAndRejectsOldCallbacksEvenAfterSameRoomRejoin() {
        var state = GroupParticipation().begin(room)
        val old = requireNotNull(state.token)
        state = state.muteSelf(old, true).block(old, "b", true)
        assertTrue(state.selfMuted); assertTrue(state.isBlocked("b"))
        state = state.stop(old)
        assertFalse(state.selfMuted); assertFalse(state.isBlocked("b"))
        state = state.begin(room)
        assertNotEquals(old, state.token)
        assertSame(state, state.stop(old))
        assertSame(state, state.muteSelf(old, true))
        assertSame(state, state.block(old, "b", true))
    }
    @Test fun transientMediaAndNetworkStateDoNotChangeMutePreferences() {
        val active = GroupParticipation().begin(room)
        val token = requireNotNull(active.token)
        val muted = active.muteSelf(token, true).block(token, "b", true)
        // No network or telephone event owns these choices; only explicit current-token edits do.
        assertTrue(muted.isCurrent(token)); assertTrue(muted.selfMuted); assertTrue(muted.isBlocked("b"))
        assertFalse(muted.isBlocked("c"))
        assertThrows(IllegalStateException::class.java) { muted.begin(GroupRoomKey("other", "host")) }
    }
}
