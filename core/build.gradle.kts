import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core — pure Kotlin/JVM. No Android deps → fast unit tests, portable.
// Holds the reading model: segmentation, cadence, playback position math.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17) // match compileJava (17); JBR is 21 by default
    }
}

dependencies {
    testImplementation(libs.junit)
}
