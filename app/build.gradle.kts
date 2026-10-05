import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val versionCodeProp = (findProperty("tunnels.versionCode") as String?)?.toInt() ?: 1
val versionNameProp = (findProperty("tunnels.versionName") as String?) ?: "0.1.0-dev"

android {
    namespace = "io.github.stronghorse44.tunnels"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.stronghorse44.tunnels"
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeProp
        versionName = versionNameProp
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Committed, publicly known key: CI debug builds keep one signature so each installs over the last.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Installs next to the release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Kept off for now: readable, reproducible output while the app is small.
            isMinifyEnabled = false
            // No commit stamp in META-INF: the APK can be rebuilt from a source tree that has no .git.
            vcsInfo { include = false }
            // No signing config, on purpose: Gradle never sees the release key. assembleRelease writes
            // app-release-unsigned.apk, which release.yml's `sign` job aligns and signs (docs/RELEASING.md).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val verifyPermissions = tasks.register("verifyPermissions") {
    group = "verification"
    description = "Checks every variant's merged manifest against the union of module permissions.allow files."
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<VerifyAppPermissionsTask>("verifyPermissions$cap") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowFiles.from(
                rootProject.fileTree(rootDir) {
                    include("tunnels/*/permissions.allow", "core/*/permissions.allow", "app/permissions.allow")
                    exclude("**/build/**", ".gradle/**", "**/.git/**")
                },
            )
            applicationId.set(variant.applicationId)
            unionFile.set(layout.buildDirectory.file("reports/permissions/allowed-${variant.name}.txt"))
        }
        verifyPermissions.configure { dependsOn(task) }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(task) }
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyPermissions) }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:metro"))
    implementation(project(":core:store"))
    implementation(project(":core:runtime"))
    implementation(project(":tunnels:installer"))
    implementation(project(":tunnels:unzip"))
    implementation(project(":tunnels:permissions"))
    implementation(project(":tunnels:apk"))
    implementation(project(":tunnels:hardening"))
    implementation(project(":tunnels:doors"))
    implementation(project(":tunnels:truststore"))
    implementation(project(":tunnels:syspackages"))
    implementation(project(":tunnels:silicon"))
    implementation(project(":tunnels:explore"))
    implementation(project(":tunnels:snapshots"))
    implementation(project(":tunnels:timeline"))
    implementation(project(":tunnels:notifications"))
    implementation(project(":tunnels:traffic"))
    implementation(project(":tunnels:surroundings"))
    implementation(project(":tunnels:homenet"))
    implementation(project(":tunnels:deepmode"))
    implementation(project(":tunnels:updater"))
    implementation(project(":tunnels:crossroads"))
    implementation(project(":tunnels:watch"))
    implementation(project(":tunnels:devicecheck"))
    implementation(project(":tunnels:pairing"))
    implementation(project(":tunnels:backups"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
