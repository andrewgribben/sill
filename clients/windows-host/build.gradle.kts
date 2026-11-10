// Sill for Windows, phase 1: a Desktop-only home-door host, built on clients/core. No third-party
// dependency: the Kotlin stdlib and the JDK (ScreenCaptureKit's and VideoToolbox's job is done by an
// external ffmpeg process; AWT's Robot does input; Win32 through Java 22's FFM, java.lang.foreign).
// Compiled by a JDK 22 toolchain (Gradle finds an installed one), run by any Java 22 or later.
plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(22) }

application {
    mainClass.set("me.saffer.sill.windows.MainKt")
    // Robot and AWT's screen bounds in physical pixels on a scaled display, as ddagrab captures them.
    applicationDefaultJvmArgs = listOf("-Dsun.java2d.uiScale=1", "--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":core"))
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
