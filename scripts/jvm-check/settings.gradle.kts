// Scratch build for the plain-Kotlin modules: compiles locally against Maven Central only.
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
rootProject.name = "jvm-check"
val root = file("../..")
root.resolve("core").listFiles()!!.filter { dir ->
    dir.resolve("build.gradle.kts").let { it.exists() && !it.readText().contains("android") }
}.sortedBy { it.name }.forEach { dir ->
    include(":core:${dir.name}")
    project(":core:${dir.name}").projectDir = dir
}
