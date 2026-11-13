package com.kuma.motointercom

import android.media.AudioRecord
import org.webrtc.audio.JavaAudioDeviceModule

/** Reads only this module's recorder, never other applications' recording configurations.
 * Field names are pinned with WebRTC 1.3.9 and kept for R8; diagnostic failure is nonfatal.
 */
internal object NativeCaptureDiagnostics {
    data class CaptureSnapshot(val record: AudioRecord?,val thread: Thread?)
    fun captureSnapshot(module: JavaAudioDeviceModule?): CaptureSnapshot? = runCatching {
        if(module==null)CaptureSnapshot(null,null) else {
            val input=module.javaClass.getDeclaredField("audioInput").run {isAccessible=true;get(module)}
            val record=input.javaClass.getDeclaredField("audioRecord").run {isAccessible=true;get(input) as AudioRecord?}
            val thread=input.javaClass.getDeclaredField("audioThread").run {isAccessible=true;get(input) as Thread?}
            CaptureSnapshot(record,thread)
        }
    }.getOrNull()
    fun recordingStopped(module: JavaAudioDeviceModule?,previous: Collection<Thread> = emptyList()): Boolean = runCatching {
        val snapshot=captureSnapshot(module) ?: return false
        (snapshot.record==null || snapshot.record.recordingState==AudioRecord.RECORDSTATE_STOPPED) &&
            (snapshot.thread==null || !snapshot.thread.isAlive) && previous.none {it.isAlive}
    }.getOrDefault(false)
    fun currentProducer(module: JavaAudioDeviceModule?, recording: Boolean): Thread? = runCatching {
        if (module == null) null else {
            val side = if (recording) "audioInput" else "audioOutput"
            val io = module.javaClass.getDeclaredField(side).run { isAccessible = true; get(module) }
            io.javaClass.getDeclaredField("audioThread").run { isAccessible = true; get(io) as? Thread }
        }
    }.getOrNull()

    fun isCurrentProducer(module: JavaAudioDeviceModule?, recording: Boolean, thread: Thread) =
        currentProducer(module, recording) === thread

    fun routedDeviceType(module: JavaAudioDeviceModule?): Int? = runCatching {
        recorder(module)?.routedDevice?.type
    }.getOrNull()

    private fun recorder(module: JavaAudioDeviceModule?): AudioRecord? {
        if (module == null) return null
        val input = module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
        return input.javaClass.getDeclaredField("audioRecord").run { isAccessible = true; get(input) as? AudioRecord }
    }

    fun describe(module: JavaAudioDeviceModule?): String = try {
        if (module == null) "input=unavailable" else {
            val record = recorder(module)
            if (record == null) "input=not-started" else {
                val device = record.routedDevice
                "input source=${record.audioSource} session=${record.audioSessionId} " +
                    "deviceType=${device?.type} deviceId=${device?.id} " +
                    "format=${record.audioFormat} rate=${record.sampleRate} channels=${record.channelCount} " +
                    "recordingState=${record.recordingState}"
            }
        }
    } catch (error: Exception) { "input=metadata-unavailable:${error.javaClass.simpleName}" }
}
