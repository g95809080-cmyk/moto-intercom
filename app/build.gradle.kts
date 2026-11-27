import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val semanticVersion = releaseVersion.getProperty("version")
val approvedCommit = releaseVersion.getProperty("approvedCommit")
require(semanticVersion.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")))
require(approvedCommit.matches(Regex("[0-9a-f]{40}")))
val androidVersionCode = releaseVersion.getProperty("versionCode").toInt()
require(androidVersionCode > 0)
val betaVersion = releaseVersion.getProperty("betaVersion")
val betaVersionCode = releaseVersion.getProperty("betaVersionCode").toInt()
val betaCommit = releaseVersion.getProperty("betaCommit")
require(betaCommit.matches(Regex("[0-9a-f]{40}")))
val groupPreviewVersion = releaseVersion.getProperty("groupPreviewVersion")
val groupPreviewVersionCode = releaseVersion.getProperty("groupPreviewVersionCode").toInt()
val groupPreviewCommit = releaseVersion.getProperty("groupPreviewCommit")
require(groupPreviewCommit.matches(Regex("[0-9a-f]{40}")))
require(groupPreviewVersionCode > androidVersionCode && groupPreviewVersionCode > betaVersionCode)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}
fun resolveExplicitSigningValue(key: String): String? =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
val explicitReleaseSigning = listOf(
    resolveExplicitSigningValue("MOTOCOM_RELEASE_STORE_FILE"),
    resolveExplicitSigningValue("MOTOCOM_RELEASE_STORE_PASSWORD"),
    resolveExplicitSigningValue("MOTOCOM_RELEASE_KEY_ALIAS"),
    resolveExplicitSigningValue("MOTOCOM_RELEASE_KEY_PASSWORD")
)
val usesExplicitReleaseSigning = explicitReleaseSigning.any { it != null }
val resolvedReleaseSigning = if (usesExplicitReleaseSigning) {
    explicitReleaseSigning
} else {
    listOf(
        localProperties.getProperty("motocom.release.storeFile")?.takeIf { it.isNotBlank() },
        localProperties.getProperty("motocom.release.storePassword")?.takeIf { it.isNotBlank() },
        localProperties.getProperty("motocom.release.keyAlias")?.takeIf { it.isNotBlank() },
        localProperties.getProperty("motocom.release.keyPassword")?.takeIf { it.isNotBlank() }
    )
}
val hasAnyReleaseSigningConfig = resolvedReleaseSigning.any { it != null }
val hasCompleteReleaseSigningConfig = resolvedReleaseSigning.all { it != null }
val releaseStoreFile = resolvedReleaseSigning[0]?.let { rootProject.file(it) }
if (hasAnyReleaseSigningConfig) {
    require(hasCompleteReleaseSigningConfig) {
        "正式分发签名配置不完整：必须同时提供 storeFile、storePassword、keyAlias 与 keyPassword。"
    }
    require(releaseStoreFile != null && releaseStoreFile.isFile) {
        "正式分发签名 keystore 文件不存在: ${releaseStoreFile?.path ?: resolvedReleaseSigning[0]}"
    }
    require(!releaseStoreFile.name.equals("debug.keystore", ignoreCase = true)) {
        "禁止将 debug.keystore 用作正式分发签名证书: ${releaseStoreFile.path}"
    }
}
val volcOmittedPlugins = listOf(
    "libbytertc_videosr_extension.so",
    "libbytertc_videosharpen_extension.so",
    "libbytertc_videodenoise_extension.so",
    "libbmf_mods.so",
    "libbmf_hydra.so",
    "libh265enc.so",
    "libbytertc_vp8codec_extension.so",
    "libbytertc_fdk-aac_extension.so",
    "libbytertc_ffmpeg_audio_extension.so",
    "libbdaudioeffect.so",
    "libbytertc_nico_extension.so",
    "libbytertc_license_extension.so"
)

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
        buildConfig = true
    }

    packaging {
        jniLibs {
            excludes += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                .flatMap { abi -> volcOmittedPlugins.map { plugin -> "lib/$abi/$plugin" } }
        }
    }

    ndkVersion = "28.2.13676358"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        buildConfigField("boolean", "RNNOISE_BETA", "false")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()

    signingConfigs {
        if (hasCompleteReleaseSigningConfig) {
            create("releaseDistribution") {
                storeFile = releaseStoreFile
                storePassword = resolvedReleaseSigning[1]
                keyAlias = resolvedReleaseSigning[2]
                keyPassword = resolvedReleaseSigning[3]
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        create("beta") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".rnnoise.beta"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "RNNOISE_BETA", "true")
        }
        create("groupPreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".group.preview"
            matchingFallbacks += "debug"
            buildConfigField("String", "GROUP_CLOUD_BASE_URL", "\"https://group.toneride.top\"")
            buildConfigField("String", "GROUP_CLOUD_ALLOWED_HOSTS", "\"group.toneride.top\"")
        }
        create("groupPreviewRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".group.preview"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("releaseDistribution")
            matchingFallbacks += "release"
            buildConfigField("String", "GROUP_CLOUD_BASE_URL", "\"https://group.toneride.top\"")
            buildConfigField("String", "GROUP_CLOUD_ALLOWED_HOSTS", "\"group.toneride.top\"")
        }
        getByName("release") {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("groupPreview") {
            res.directories.add("src/groupPreviewCommon/res")
        }
        getByName("groupPreviewRelease") {
            java.srcDirs("src/groupPreview/java")
            kotlin.srcDirs("src/groupPreview/java")
            manifest.srcFile("src/groupPreview/AndroidManifest.xml")
            jniLibs.srcDirs("src/groupPreview/jniLibs")
            res.directories.add("src/groupPreviewCommon/res")
        }
    }
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("beta")) {
    (it as com.android.build.api.variant.HasHostTestsBuilder)
        .hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
}
androidComponents.onVariants(androidComponents.selector().withBuildType("beta")) { variant ->
    variant.outputs.forEach {
        it.versionName.set("$betaVersion+${betaCommit.take(7)}")
        it.versionCode.set(betaVersionCode)
    }
}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("groupPreview")) {
    (it as com.android.build.api.variant.HasHostTestsBuilder)
        .hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("groupPreviewRelease")) {
    (it as com.android.build.api.variant.HasHostTestsBuilder)
        .hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
}
androidComponents.onVariants(androidComponents.selector().withBuildType("groupPreview")) { variant ->
    variant.androidTest?.applicationId?.set("com.kuma.motointercom.group.preview.instrumentation")
    variant.outputs.forEach {
        it.versionName.set("$groupPreviewVersion+${groupPreviewCommit.take(7)}")
        it.versionCode.set(groupPreviewVersionCode)
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("groupPreviewRelease")) { variant ->
    variant.androidTest?.applicationId?.set("com.kuma.motointercom.group.preview.instrumentation")
    variant.outputs.forEach {
        it.versionName.set("$groupPreviewVersion+${groupPreviewCommit.take(7)}")
        it.versionCode.set(groupPreviewVersionCode)
    }
}
val releaseSigningReady = hasCompleteReleaseSigningConfig
val releasePackagingTasks = setOf(
    "assembleGroupPreviewRelease",
    "packageGroupPreviewRelease",
    "bundleGroupPreviewRelease",
    "signGroupPreviewReleaseBundle",
    "validateSigningGroupPreviewRelease"
)
tasks.configureEach {
    if (name in releasePackagingTasks) {
        doFirst {
            if (!releaseSigningReady) {
                throw GradleException(
                    "groupPreviewRelease 打包必须配置正式分发签名（MOTOCOM_RELEASE_STORE_FILE / MOTOCOM_RELEASE_STORE_PASSWORD / MOTOCOM_RELEASE_KEY_ALIAS / MOTOCOM_RELEASE_KEY_PASSWORD 或 local.properties 中的 motocom.release.*），禁止回退到 Android Debug 或输出未签名包。"
                )
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// stream-webrtc-android 1.3.9 exposes this setter but never connects it to the recorder.
androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        com.kuma.buildlogic.WebRtcCaptureCallbackPatch::class.java,
        com.android.build.api.instrumentation.InstrumentationScope.ALL
    ) {}
    variant.instrumentation.setAsmFramesComputationMode(
        com.android.build.api.instrumentation.FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
    )
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    if (name == "testGroupPreviewUnitTest") {
        include("**/group/**", "**/Group*Test.class", "**/Rider*Test.class",
            "**/DecodedAudioPlayoutTest.class", "**/PcmTransmitGateTest.class",
            "**/NativeAudioProducerOwnerTest.class")
    }
    if (name == "testGroupPreviewReleaseUnitTest") {
        include("**/GroupReleaseCloudPolicyTest.class")
    }
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
    add("groupPreviewImplementation", "org.bouncycastle:bcprov-jdk18on:1.86")
    add("groupPreviewImplementation", "com.volcengine:VolcEngineRTC:3.60.108.100") {
        exclude(group = "com.android.support")
        exclude(group = "android.arch.core")
        exclude(group = "android.arch.lifecycle")
    }
    add("groupPreviewReleaseImplementation", "org.bouncycastle:bcprov-jdk18on:1.86")
    add("groupPreviewReleaseImplementation", "com.volcengine:VolcEngineRTC:3.60.108.100") {
        exclude(group = "com.android.support")
        exclude(group = "android.arch.core")
        exclude(group = "android.arch.lifecycle")
    }
    implementation("io.getstream:stream-webrtc-android:1.3.9") {
        version { strictly("1.3.9") }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    ksp("androidx.room:room-compiler:2.7.2")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("betaImplementation", "androidx.compose.ui:ui-test-manifest")
    add("groupPreviewImplementation", "androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
