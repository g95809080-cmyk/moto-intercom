package com.kuma.motointercom

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

internal data class AudioNoiseRuntimeSnapshot(
    val deviceModel: String,
    val requestedMode: AudioNoiseReductionMode,
    val activeMode: AudioNoiseReductionMode,
    val bluetoothProfile: AudioBluetoothProfile,
    val activeBluetoothProfile: AudioBluetoothProfile,
    val activeRouteDeviceType: Int?,
    val activeRouteDeviceName: String,
    val hardwareAecAvailable: Boolean,
    val hardwareNsAvailable: Boolean,
    val hardwareAecRequested: Boolean,
    val hardwareNsRequested: Boolean,
    val sampleRate: Int,
    val channelCount: Int,
    val audioFormat: Int,
    val frameBytes: Int,
    val frameCount: Long,
    val overrunCount: Long,
    val externalProcessorOverrunCount: Long,
    val p95ProcessingNanos: Long,
    val p95ExternalProcessingNanos: Long,
    val maxProcessingNanos: Long,
    val maxExternalProcessingNanos: Long,
    val externalProcessorFrameCount: Long,
    val fallbackReason: String?
) {
    fun toLogLine(): String = String.format(
        Locale.US,
        "audio_noise_diagnostics model=%s requested=%s active=%s profile=%s " +
            "activeProfile=%s activeRouteType=%s activeRouteName=%s " +
            "hwAecAvailable=%s hwAecRequested=%s hwNsAvailable=%s hwNsRequested=%s " +
            "sampleRate=%d channels=%d format=%d frameBytes=%d " +
            "frames=%d overruns=%d externalOverruns=%d p95CallbackMs=%.2f " +
            "p95ExternalMs=%.2f maxCallbackMs=%.2f maxExternalMs=%.2f " +
            "externalFrames=%d fallback=%s",
        deviceModel,
        requestedMode,
        activeMode,
        bluetoothProfile.logValue,
        activeBluetoothProfile.logValue,
        activeRouteDeviceType ?: "none",
        activeRouteDeviceName.ifBlank { "-" },
        hardwareAecAvailable,
        hardwareAecRequested,
        hardwareNsAvailable,
        hardwareNsRequested,
        sampleRate,
        channelCount,
        audioFormat,
        frameBytes,
        frameCount,
        overrunCount,
        externalProcessorOverrunCount,
        p95ProcessingNanos / 1_000_000.0,
        p95ExternalProcessingNanos / 1_000_000.0,
        maxProcessingNanos / 1_000_000.0,
        maxExternalProcessingNanos / 1_000_000.0,
        externalProcessorFrameCount,
        fallbackReason ?: "none"
    )
}

/** Lock-free counters and state used by the audio callback and diagnostic logger. */
internal class AudioNoiseDiagnostics(
    private val deviceModel: String,
    private val requestedMode: AudioNoiseReductionMode,
    initialActiveMode: AudioNoiseReductionMode,
    initialBluetoothProfile: AudioBluetoothProfile,
    initialActiveBluetoothProfile: AudioBluetoothProfile = AudioBluetoothProfile.UNKNOWN,
    initialActiveRouteDeviceType: Int? = null,
    initialActiveRouteDeviceName: String = "",
    private val hardwareAecAvailable: Boolean = false,
    private val hardwareNsAvailable: Boolean = false,
    initialHardwareAecRequested: Boolean = true,
    initialHardwareNsRequested: Boolean = true,
    private val frameBudgetNanos: Long = FRAME_BUDGET_NANOS
) {
    private val activeMode = AtomicReference(initialActiveMode)
    private val bluetoothProfile = AtomicReference(initialBluetoothProfile)
    private val activeBluetoothProfile = AtomicReference(initialActiveBluetoothProfile)
    private val activeRouteDeviceType = AtomicInteger(initialActiveRouteDeviceType ?: NO_DEVICE_TYPE)
    private val activeRouteDeviceName = AtomicReference(initialActiveRouteDeviceName)
    private val hardwareAecRequested = AtomicReference(initialHardwareAecRequested)
    private val hardwareNsRequested = AtomicReference(initialHardwareNsRequested)
    private val sampleRate = AtomicInteger(0)
    private val channelCount = AtomicInteger(0)
    private val audioFormat = AtomicInteger(0)
    private val frameBytes = AtomicInteger(0)
    private val frameCount = AtomicLong(0L)
    private val overrunCount = AtomicLong(0L)
    private val externalProcessorOverrunCount = AtomicLong(0L)
    private val processingHistogram = AtomicLongArray(PROCESSING_BUCKET_COUNT)
    private val externalProcessingHistogram = AtomicLongArray(PROCESSING_BUCKET_COUNT)
    private val maxProcessingNanos = AtomicLong(0L)
    private val maxExternalProcessingNanos = AtomicLong(0L)
    private val externalProcessorFrameCount = AtomicLong(0L)
    private val fallbackReason = AtomicReference<String?>(null)

    fun updateBluetoothProfile(profile: AudioBluetoothProfile) {
        bluetoothProfile.set(profile)
    }

    fun activate(mode: AudioNoiseReductionMode) {
        activeMode.set(mode)
    }

    fun updateHardwareRequests(aecRequested: Boolean, nsRequested: Boolean) {
        hardwareAecRequested.set(aecRequested)
        hardwareNsRequested.set(nsRequested)
    }

    fun updateActiveRoute(probe: AudioBluetoothRouteProbe) {
        bluetoothProfile.set(probe.availableProfile)
        activeBluetoothProfile.set(probe.activeProfile)
        activeRouteDeviceType.set(probe.activeDeviceType ?: NO_DEVICE_TYPE)
        activeRouteDeviceName.set(probe.activeDeviceName)
    }

    fun recordFallback(reason: String) {
        activeMode.set(AudioNoiseReductionMode.BASELINE)
        fallbackReason.compareAndSet(null, reason)
    }

    fun recordAudioFrame(
        sampleRate: Int,
        channelCount: Int,
        audioFormat: Int,
        frameBytes: Int,
        processingNanos: Long
    ): Long {
        this.sampleRate.set(sampleRate)
        this.channelCount.set(channelCount)
        this.audioFormat.set(audioFormat)
        this.frameBytes.set(frameBytes)
        val count = frameCount.incrementAndGet()
        recordProcessingLatency(processingNanos, isExternal = false)
        return count
    }

    /** Returns false exactly when the external processor must stay bypassed. */
    fun recordExternalProcessorFrame(processingNanos: Long = 0L): Boolean {
        externalProcessorFrameCount.incrementAndGet()
        recordProcessingLatency(processingNanos, isExternal = true)
        if (processingNanos > frameBudgetNanos) {
            externalProcessorOverrunCount.incrementAndGet()
            recordFallback("external_processor_overrun")
            return false
        }
        return activeMode.get() == AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING
    }

    fun snapshot(): AudioNoiseRuntimeSnapshot = AudioNoiseRuntimeSnapshot(
        deviceModel = deviceModel,
        requestedMode = requestedMode,
        activeMode = activeMode.get(),
        bluetoothProfile = bluetoothProfile.get(),
        activeBluetoothProfile = activeBluetoothProfile.get(),
        activeRouteDeviceType = activeRouteDeviceType.get()
            .takeUnless { it == NO_DEVICE_TYPE },
        activeRouteDeviceName = activeRouteDeviceName.get(),
        hardwareAecAvailable = hardwareAecAvailable,
        hardwareNsAvailable = hardwareNsAvailable,
        hardwareAecRequested = hardwareAecRequested.get(),
        hardwareNsRequested = hardwareNsRequested.get(),
        sampleRate = sampleRate.get(),
        channelCount = channelCount.get(),
        audioFormat = audioFormat.get(),
        frameBytes = frameBytes.get(),
        frameCount = frameCount.get(),
        overrunCount = overrunCount.get(),
        externalProcessorOverrunCount = externalProcessorOverrunCount.get(),
        p95ProcessingNanos = percentileNanos(processingHistogram, 0.95),
        p95ExternalProcessingNanos = percentileNanos(externalProcessingHistogram, 0.95),
        maxProcessingNanos = maxProcessingNanos.get(),
        maxExternalProcessingNanos = maxExternalProcessingNanos.get(),
        externalProcessorFrameCount = externalProcessorFrameCount.get(),
        fallbackReason = fallbackReason.get()
    )

    private fun recordProcessingLatency(processingNanos: Long, isExternal: Boolean) {
        val normalized = processingNanos.coerceAtLeast(0L)
        val histogram = if (isExternal) externalProcessingHistogram else processingHistogram
        val max = if (isExternal) maxExternalProcessingNanos else maxProcessingNanos
        histogram.incrementAndGet(bucketFor(normalized))
        max.updateMax(normalized)
        if (!isExternal && normalized > frameBudgetNanos) {
            overrunCount.incrementAndGet()
        }
    }

    private fun percentileNanos(
        histogram: AtomicLongArray,
        percentile: Double
    ): Long {
        var total = 0L
        for (index in 0 until PROCESSING_BUCKET_COUNT) {
            total += histogram.get(index)
        }
        if (total == 0L) return 0L
        val target = (total * percentile).toLong().coerceAtLeast(1L)
        var seen = 0L
        for (index in 0 until PROCESSING_BUCKET_COUNT) {
            seen += histogram.get(index)
            if (seen >= target) return bucketUpperBound(index)
        }
        return bucketUpperBound(PROCESSING_BUCKET_COUNT - 1)
    }

    private fun bucketFor(nanos: Long): Int {
        if (nanos <= 1L) return 0
        return (63 - java.lang.Long.numberOfLeadingZeros(nanos))
            .coerceIn(0, PROCESSING_BUCKET_COUNT - 1)
    }

    private fun bucketUpperBound(index: Int): Long =
        if (index == 0) 1L else 1L shl (index + 1)

    companion object {
        const val FRAME_BUDGET_NANOS = 10_000_000L
        private const val NO_DEVICE_TYPE = Int.MIN_VALUE
        private const val PROCESSING_BUCKET_COUNT = 32

        private fun AtomicLong.updateMax(value: Long) {
            while (true) {
                val current = get()
                if (value <= current || compareAndSet(current, value)) return
            }
        }
    }
}
