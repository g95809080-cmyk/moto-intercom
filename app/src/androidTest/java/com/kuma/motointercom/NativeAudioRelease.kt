package com.kuma.motointercom

import org.junit.Assert.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Waits for this fixture's real disposal receipt; never modifies production ownership. */
internal class NativeAudioRelease(private val label: String) {
    private val disposed = CountDownLatch(1)
    private lateinit var engine: RiderAudioEngine
    private lateinit var token: Any
    private lateinit var rtc: ExecutorService
    private val producers = mutableSetOf<Thread>()

    fun onDisposed() { disposed.countDown() }

    fun own(value: RiderAudioEngine): RiderAudioEngine = value.also {
        engine = it
        token = field(it, "platformOwnership")!!
        rtc = field(it, "rtc") as ExecutorService
    }

    fun observeProducers() {
        val module = field(engine, "audioDeviceModule") as? JavaAudioDeviceModule
        listOf(true, false).forEach { recording ->
            NativeCaptureDiagnostics.currentProducer(module, recording)?.let(producers::add)
        }
        @Suppress("UNCHECKED_CAST")
        val sessions = field(engine, "activeSessionSnapshot") as List<RiderMediaSession>
        sessions.forEach { session ->
            val output = field(session, "playout") as DecodedAudioPlayout
            synchronized(field(output, "lock")!!) {
                val lease = field(output, "active")
                if (lease != null) producers += field(lease, "thread") as Thread
            }
        }
    }

    fun hasOwner(): Boolean = synchronized(AudioPlatformOwnership) { owners().any { it === token } }

    fun awaitReleased() {
        assertTrue("$label missing its disposal receipt; remaining=${describeOwners()}", disposed.await(6, TimeUnit.SECONDS))
        assertTrue("$label RTC executor is still running", rtc.awaitTermination(6, TimeUnit.SECONDS))
        assertFalse("$label retained its exact token; remaining=${describeOwners()}", hasOwner())
        producers.forEach { thread ->
            thread.join(2_000)
            assertFalse("$label producer ${thread.name}/${Integer.toHexString(System.identityHashCode(thread))} is still running", thread.isAlive)
        }
        listOf("audioDeviceModule", "factory", "audioSource", "localAudioTrack").forEach {
            assertNull("$label retained $it", field(engine, it))
        }
    }

    companion object {
        fun closeAll(vararg releases: NativeAudioRelease, closeAdapters: () -> Unit = {}) {
            // Initiate every close before waiting for one; a failed assertion cannot strand another engine.
            var failure: Throwable? = null
            fun attempt(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let {
                    if (failure == null) failure = it else failure!!.addSuppressed(it)
                }
            }
            releases.forEach { attempt(it::observeProducers) }
            attempt(closeAdapters)
            releases.forEach { attempt { it.engine.close() } }
            releases.forEach { attempt(it::awaitReleased) }
            failure?.let { throw it }
        }

        fun describeOwners(): String = synchronized(AudioPlatformOwnership) {
            owners().joinToString(prefix = "[", postfix = "]") { Integer.toHexString(System.identityHashCode(it)) }
        }

        private fun owners(): List<Any> {
            @Suppress("UNCHECKED_CAST")
            return (field(AudioPlatformOwnership, "owners") as Set<Any>).toList()
        }

        private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
            isAccessible = true; get(target)
        }
    }
}
