package com.kuma.motointercom

import android.media.AudioRecord
import org.webrtc.audio.JavaAudioDeviceModule

/** Reads only this module's recorder, never other applications' recording configurations.
 * Field names are pinned with WebRTC 1.3.9 and kept for R8; diagnostic failure is nonfatal.
 */
internal object NativeCaptureDiagnostics {
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
