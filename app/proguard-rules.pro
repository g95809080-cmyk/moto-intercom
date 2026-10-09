# Only the pinned own-recorder metadata fields used by NativeCaptureDiagnostics.
-keepclassmembers class org.webrtc.audio.JavaAudioDeviceModule {
    org.webrtc.audio.WebRtcAudioRecord audioInput;
}
-keepclassmembers class org.webrtc.audio.WebRtcAudioRecord {
    android.media.AudioRecord audioRecord;
}
