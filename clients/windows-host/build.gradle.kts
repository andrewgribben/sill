// Sill for Windows, phase 1: a Desktop-only home-door host, built on clients/core. No third-party
// dependency: the Kotlin stdlib and the JDK (ScreenCaptureKit's and VideoToolbox's job is done by an
// external ffmpeg process; AWT's Robot does input). JVM 17 bytecode, run by the PC's JDK 21.
plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("me.saffer.sill.windows.MainKt")
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
