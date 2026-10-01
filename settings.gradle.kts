pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Tunnels"

include(
    ":app",
    ":core:model",
    ":core:engine",
    ":core:archive",
    ":core:install",
    ":core:store",
    ":core:common",
    ":tunnels:installer",
    ":tunnels:unzip",
)
