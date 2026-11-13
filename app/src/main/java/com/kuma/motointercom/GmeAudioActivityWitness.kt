package com.kuma.motointercom

import android.content.Context
import android.media.AudioManager
import android.os.Build

internal data class AudioActivities(val recording: Set<Any>,val playback: Set<Any>)

// Capture unrelated activity before SDK initialization. Never learn a new
// "unrelated" player while GME could own it. Unidentified later activity defers
// handover rather than granting a second recorder. No samples are inspected.
internal class GmeAudioActivityWitness(private val read: ()->AudioActivities?) {
    private val baseline=runCatching {read()}.getOrNull()
    fun idle(): Boolean {
        val before=baseline ?: return false
        val current=runCatching {read()}.getOrNull() ?: return false
        return before.recording.containsAll(current.recording) && before.playback.containsAll(current.playback)
    }
    companion object {
        fun from(context: Context)=GmeAudioActivityWitness {
            if(Build.VERSION.SDK_INT<26)null else {
                val manager=context.applicationContext.getSystemService(AudioManager::class.java)
                AudioActivities(manager.activeRecordingConfigurations.toSet(),manager.activePlaybackConfigurations.toSet())
            }
        }
    }
}
