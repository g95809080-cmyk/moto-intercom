plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val semanticVersion = releaseVersion.getProperty("version")
val approvedCommit = releaseVersion.getProperty("approvedCommit")
require(semanticVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")))
require(approvedCommit.matches(Regex("[0-9a-f]{40}")))
val androidVersionCode = releaseVersion.getProperty("versionCode").toInt()
require(androidVersionCode > 0)

android {
    namespace = "com.kuma.motointercom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kuma.motointercom"
        minSdk = 23
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = "$semanticVersion+${approvedCommit.take(7)}"
        testApplicationId = "com.kuma.motointercom.instrumentation"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric's API 23/32/33/35 sandboxes exceed the default test JVM heap.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.window:window-core:1.4.0")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    ksp("androidx.room:room-compiler:2.7.2")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
