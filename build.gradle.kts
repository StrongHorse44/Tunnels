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
    dependsOn(provider {
        subprojects.filter { it.plugins.hasPlugin("com.android.library") || it.plugins.hasPlugin("com.android.application") }
            .map { "${it.path}:verifyPermissions" }
    })
}

/**
 * Every module's compiled code is checked for network and shell references that buildSrc/egress.allow does not grant it
 * (hard rule 1; audit M2: INTERNET belongs to the whole app, so the manifest check alone cannot keep sockets in the four
 * network modules, and Deep mode's shell runs outside the Network toggle). Android modules check each variant's main
 * classes before that variant assembles; plain-Kotlin modules check their main classes before they assemble or check;
 * the app's assemble tasks wait for every module's check, so `assembleDebug` and `assembleRelease` run all of them.
 */
val egressAllowFile = layout.projectDirectory.file(Egress.ALLOW_FILE)
val egressVariants = listOf("Debug", "Release")

val egressModules = provider { subprojects.map { it.projectDir.relativeTo(rootDir).path.replace('\\', '/') } }

subprojects {
    val module = projectDir.relativeTo(rootDir).path.replace('\\', '/')
    val mainSources = fileTree("src/main") { include("**/*.kt", "**/*.java", "**/*.aidl") }
    fun registerEgress(name: String, classes: Any, producers: Provider<List<String>>) = tasks.register<VerifyModuleEgressTask>(name) {
        group = "verification"
        description = "Checks $module's compiled main classes against ${Egress.ALLOW_FILE}."
        classDirs.from(classes)
        classProducers.set(producers)
        hasMainSources.set(provider { !mainSources.isEmpty })
        knownModules.set(egressModules)
        allowFile.set(egressAllowFile)
        modulePath.set(module)
        report.set(layout.buildDirectory.file("reports/egress/$name.txt"))
    }

    plugins.withType<JavaPlugin> {
        // Plain-Kotlin modules. (No Android module applies the java plugin; the guard keeps one from failing the build.)
        if ("verifyEgress" !in tasks.names) {
            val main = extensions.getByType<SourceSetContainer>().getByName("main")
            val verify = registerEgress("verifyEgress", main.output.classesDirs, provider { listOf("sourceSets.main (${main.classesTaskName})") })
            tasks.named("assemble") { dependsOn(verify) }
            tasks.named("check") { dependsOn(verify) }
        }
    }

    listOf("com.android.library", "com.android.application").forEach { id ->
        plugins.withId(id) {
            val perVariant = egressVariants.map { v ->
                // The variant's Kotlin and Java (AIDL, Room) compile tasks; their outputs are its main classes.
                val compiled = tasks.matching { it.name == "compile${v}Kotlin" || it.name == "compile${v}JavaWithJavac" }
                // An empty set (AGP or KGP renamed a task, or flavors were added) fails the check instead of passing it.
                registerEgress("verifyEgress$v", compiled, provider { compiled.names.toList() }).also { verify ->
                    tasks.matching { it.name == "assemble$v" }.configureEach { dependsOn(verify) }
                }
            }
            if ("verifyEgress" !in tasks.names) {
                tasks.register("verifyEgress") {
                    group = "verification"
                    description = "Checks $module's compiled main classes of every variant against ${Egress.ALLOW_FILE}."
                    dependsOn(perVariant)
                }
            }
            tasks.matching { it.name == "check" }.configureEach { dependsOn(perVariant) }
        }
    }

    plugins.withId("com.android.application") {
        egressVariants.forEach { v ->
            tasks.matching { it.name == "assemble$v" }.configureEach {
                dependsOn(provider {
                    rootProject.subprojects.filter { it != project }.mapNotNull { p ->
                        when {
                            p.plugins.hasPlugin("com.android.library") -> "${p.path}:verifyEgress$v"
                            p.plugins.hasPlugin(JavaPlugin::class.java) -> "${p.path}:verifyEgress"
                            else -> null
                        }
                    }
                })
            }
        }
    }
}

tasks.register("verifyEgress") {
    group = "verification"
    description = "Checks every module's compiled main classes against ${Egress.ALLOW_FILE} (hard rule 1)."
    dependsOn(provider {
        subprojects.filter {
            it.plugins.hasPlugin("com.android.library") || it.plugins.hasPlugin("com.android.application") ||
                it.plugins.hasPlugin(JavaPlugin::class.java)
        }.map { "${it.path}:verifyEgress" }
    })
}
