import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Fails the build if the merged manifest asks for any forbidden permission. Hard rule #1. */
abstract class VerifyNoInternetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val forbidden = setOf("android.permission.INTERNET")
        val manifest = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name\s*=\s*"([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        val hits = declared intersect forbidden
        if (hits.isNotEmpty()) {
            throw GradleException("Merged manifest declares forbidden permission(s): ${hits.joinToString()}. Tunnels must stay offline.")
        }
        report.get().asFile.writeText(declared.sorted().joinToString("\n", postfix = "\n"))
        logger.lifecycle("No INTERNET permission. Declared: ${declared.sorted().joinToString()}")
    }
}

val versionCodeProp = (findProperty("tunnels.versionCode") as String?)?.toInt() ?: 1
val versionNameProp = (findProperty("tunnels.versionName") as String?) ?: "0.1.0-dev"
val keystorePath: String? = System.getenv("TUNNELS_KEYSTORE")

android {
    namespace = "io.github.stronghorse44.tunnels"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.stronghorse44.tunnels"
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    signingConfigs {
        // Committed, publicly known key: CI debug builds keep one signature so each installs over the last.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TUNNELS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TUNNELS_KEY_ALIAS")
                keyPassword = System.getenv("TUNNELS_KEY_PASSWORD")
            }
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
            signingConfig = signingConfigs.findByName("release")
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

val verifyNoInternet = tasks.register("verifyNoInternet") {
    group = "verification"
    description = "Checks every variant's merged manifest for forbidden permissions."
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<VerifyNoInternetTask>("verifyNoInternet$cap") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }
        verifyNoInternet.configure { dependsOn(task) }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(task) }
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyNoInternet) }

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:store"))
    implementation(project(":tunnels:installer"))
    implementation(project(":tunnels:unzip"))
}
