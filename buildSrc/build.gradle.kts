plugins { `java-library` }

repositories { google(); mavenCentral() }

dependencies {
    implementation("com.android.tools.build:gradle:9.3.1")
    implementation("org.ow2.asm:asm:9.8")
}
