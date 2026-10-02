plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.stronghorse44.tunnels.crossroads"
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
    implementation(project(":core:crossrules"))
    testImplementation(libs.junit)
    // CrossKeys mirrors a few Timeline and Deep mode keys; the unit tests check them against their owners.
    testImplementation(project(":tunnels:timeline"))
    testImplementation(project(":tunnels:deepmode"))
    testImplementation(project(":core:permrules"))
    testImplementation(project(":core:trackers"))
    testImplementation(project(":core:dns"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
