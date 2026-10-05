plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.stronghorse44.tunnels.snapshots"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:runtime"))
    implementation(project(":core:store"))
    implementation(project(":core:export"))
    // The confirmed-network row (ConfirmedNetworkCodec, ConfirmedNetworkBook) is owned by core:lan, shared with Home network.
    implementation(project(":core:lan"))
    // An import that carries the background-check settings re-arms (or cancels) the scheduled job (WatchScheduler).
    implementation(project(":tunnels:watch"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
