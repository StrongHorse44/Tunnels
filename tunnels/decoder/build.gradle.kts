plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The sealed decoder (audit M6): an isolatedProcess service that decodes images and renders PDF pages for Convert, and
// the host client that talks to it. It depends on core:decoder only, so the sealed process cannot link the store,
// staging or runtime code.
android {
    namespace = "io.github.stronghorse44.tunnels.decoder"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // The service speaks AIDL (src/main/aidl), as Deep mode's shell does.
        aidl = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // api: DecoderClient's signatures use core:decoder's types (ImageKind, FrameLimits, DecodeStatus).
    api(project(":core:decoder"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
