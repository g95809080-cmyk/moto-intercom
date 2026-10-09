package com.kuma.motointercom

import android.media.AudioRecord
import org.webrtc.audio.JavaAudioDeviceModule

/** Reads only this module's recorder, never other applications' recording configurations.
 * Field names are pinned with WebRTC 1.3.9 and kept for R8; diagnostic failure is nonfatal.
 */
internal object NativeCaptureDiagnostics {
    fun isCurrentProducer(module: JavaAudioDeviceModule?, recording: Boolean, thread: Thread): Boolean = runCatching {
        if (module == null) false else {
            val side = if (recording) "audioInput" else "audioOutput"
            val io = module.javaClass.getDeclaredField(side).run { isAccessible = true; get(module) }
            io.javaClass.getDeclaredField("audioThread").run { isAccessible = true; get(io) === thread }
        }
    }.getOrDefault(false)

    fun describe(module: JavaAudioDeviceModule?): String = try {
        if (module == null) "input=unavailable" else {
            val input = module.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
            val record = input.javaClass.getDeclaredField("audioRecord").run { isAccessible = true; get(input) as? AudioRecord }
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
