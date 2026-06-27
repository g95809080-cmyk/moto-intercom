plugins {
    id("com.android.application")
}

android {
    namespace = "com.kuma.motointercom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kuma.motointercom"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.getstream:stream-webrtc-android:1.3.9")
}
