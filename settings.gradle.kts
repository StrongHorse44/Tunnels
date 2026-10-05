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
    ":core:metro",
    ":core:engine",
    ":core:archive",
    ":core:install",
    ":core:store",
    ":core:common",
    ":core:runtime",
    ":core:permrules",
    ":core:trackers",
    ":core:elf",
    ":core:certs",
    ":core:syspkg",
    ":core:attestation",
    ":core:export",
    ":core:notifrules",
    ":core:dns",
    ":core:ble",
    ":core:lan",
    ":core:updates",
    ":core:crossrules",
    ":core:watchrules",
    ":core:devicecheck",
    ":core:pairing",
    ":core:backups",
    ":core:posture",
    ":tunnels:installer",
    ":tunnels:unzip",
    ":tunnels:permissions",
    ":tunnels:apk",
    ":tunnels:hardening",
    ":tunnels:doors",
    ":tunnels:truststore",
    ":tunnels:syspackages",
    ":tunnels:silicon",
    ":tunnels:explore",
    ":tunnels:snapshots",
    ":tunnels:timeline",
    ":tunnels:notifications",
    ":tunnels:traffic",
    ":tunnels:surroundings",
    ":tunnels:homenet",
    ":tunnels:deepmode",
    ":tunnels:updater",
    ":tunnels:crossroads",
    ":tunnels:watch",
    ":tunnels:devicecheck",
    ":tunnels:pairing",
    ":tunnels:backups",
)
