# Only the pinned own-recorder metadata fields used by NativeCaptureDiagnostics.
-keepclassmembers class org.webrtc.audio.JavaAudioDeviceModule {
    org.webrtc.audio.WebRtcAudioRecord audioInput;
    org.webrtc.audio.WebRtcAudioTrack audioOutput;
}
-keepclassmembers class org.webrtc.audio.WebRtcAudioRecord {
    android.media.AudioRecord audioRecord;
    *** audioThread;
}
-keepclassmembers class org.webrtc.audio.WebRtcAudioTrack {
    *** audioThread;
}
