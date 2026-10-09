package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAudioProducerOwnerTest {
    @Test fun resumeBeforeNativeStopDoesNotAuthorizeOldErrorsOrStops() {
        val owner = NativeAudioProducerOwner()
        val old = Thread()
        val fresh = Thread()
        var changes = 0
        owner.start(old, { true }) { changes++ }
        owner.authorize(false)
        owner.authorize(true)
        owner.current(old) { changes++ }
        owner.stop(old) { changes++ }
        assertEquals(1, changes)
        owner.start(fresh, { true }) { changes++ }
        owner.current(old) { changes++ }
        owner.stop(old) { changes++ }
        owner.current(fresh) { changes++ }
        assertEquals(3, changes)
    }

    @Test fun lateStartFromReplacedSdkThreadCannotClaimNewProducer() {
        val owner = NativeAudioProducerOwner()
        val old = Thread()
        val fresh = Thread()
        var changes = 0
        owner.start(fresh, { true }) { changes++ }
        owner.start(old, { false }) { changes++ }
        owner.current(old) { changes++ }
        owner.current(fresh) { changes++ }
        assertEquals(2, changes)
        owner.authorize(false)
        owner.start(fresh, { true }) { changes++ }
        owner.authorized { changes++ }
        assertEquals(2, changes)
    }
}
