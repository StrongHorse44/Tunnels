plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

/** Every Android library checks its manifest against its permissions.allow (hard rule #1). */
subprojects {
    plugins.withId("com.android.library") {
        val verify = tasks.register<VerifyModulePermissionsTask>("verifyPermissions") {
            group = "verification"
            manifest.set(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
            allowFile.set(layout.projectDirectory.file("permissions.allow"))
            modulePath.set(projectDir.relativeTo(rootDir).path.replace('\\', '/'))
            report.set(layout.buildDirectory.file("reports/permissions/declared.txt"))
        }
        tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verify) }
    }
}

tasks.register("verifyPermissions") {
    group = "verification"
    description = "Checks every module's manifest against its permissions.allow and the app's merged manifest against their union."
    dependsOn(subprojects.map { "${it.path}:verifyPermissions" }.filter { path ->
        subprojects.any { it.path == path.substringBeforeLast(':') && (it.plugins.hasPlugin("com.android.library") || it.plugins.hasPlugin("com.android.application")) }
    })
}
