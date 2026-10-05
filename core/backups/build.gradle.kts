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
    // Rules helpers, and the codec's header reader (the B05a copy in core:export; never a second copy).
    implementation(project(":core:engine"))
    implementation(project(":core:export"))
    testImplementation(libs.junit)
}
