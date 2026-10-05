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
    // Settings, pairing pins and network fingerprints are validated with the code that owns them.
    implementation(project(":core:dns"))
    implementation(project(":core:watchrules"))
    implementation(project(":core:pairing"))
    testImplementation(libs.junit)
}
