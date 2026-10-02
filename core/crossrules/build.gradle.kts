plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:engine"))
    // Source tunnels whose key schemas live in plain-Kotlin modules. Timeline's and Deep mode's live in their
    // Android modules, so CrossKeys mirrors the few it reads (checked against them in tunnels:crossroads' tests).
    implementation(project(":core:permrules"))
    implementation(project(":core:trackers"))
    implementation(project(":core:dns"))
    testImplementation(libs.junit)
}
