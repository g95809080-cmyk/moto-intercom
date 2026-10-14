plugins {
    // AGP 9.3.1 is supplied by buildSrc, which implements the pinned WebRTC capture patch.
    id("com.android.application") apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
