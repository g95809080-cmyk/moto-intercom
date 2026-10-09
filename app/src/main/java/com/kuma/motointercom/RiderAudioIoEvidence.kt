package com.kuma.motointercom

/** Native callbacks and fresh PCM, rather than requested I/O switches. */
internal class RiderAudioIoEvidence {
    private var recording = false
    private var playing = false
    private var pcmAt = Long.MIN_VALUE
    private var revision = 0L
    @Synchronized fun recording(value: Boolean) { recording = value; pcmAt = Long.MIN_VALUE; revision++ }
    @Synchronized fun playing(value: Boolean) { playing = value; revision++ }
    @Synchronized fun pcm(now: Long) { if (recording) pcmAt = now }
    @Synchronized fun revision() = revision
    @Synchronized fun ready(expected: Long, now: Long) = expected == revision && recording && playing &&
        pcmAt != Long.MIN_VALUE && now >= pcmAt && now - pcmAt <= 2_000
}
