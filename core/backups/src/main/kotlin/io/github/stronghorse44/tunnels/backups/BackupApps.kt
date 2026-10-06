package io.github.stronghorse44.tunnels.backups

/**
 * An app that writes FWX bundles (app ID registry, export container spec section 7). [packages] are the known package
 * names, tried in order when the user taps Open; an app with none (the Pusher server script) has no screen on this
 * phone. [isThisApp] marks Tunnels itself.
 */
data class BackupApp(
    val id: String,
    val name: String,
    val packages: List<String> = emptyList(),
    val isThisApp: Boolean = false,
)

object BackupApps {
    val all: List<BackupApp> = listOf(
        BackupApp("tunnels", "Tunnels", listOf("io.github.stronghorse44.tunnels", "io.github.stronghorse44.tunnels.debug"), isThisApp = true),
        BackupApp("lumen", "Lumen", listOf("com.rjmack.lumen")),
        BackupApp("southbound", "Southbound", listOf("app.southbound")),
        BackupApp("mardigras", "Mardi Gras", listOf("dev.rj.mardigras")),
        BackupApp("pusher", "Pusher", listOf("com.stronghorse44.pusher")),
        BackupApp("pusher-server", "Pusher server"),
        BackupApp("prikey", "Prikey", listOf("io.github.stronghorse44.prikey")),
        BackupApp("linx", "Linx", listOf("io.github.stronghorse44.linx")),
    )

    fun byId(id: String): BackupApp? = all.firstOrNull { it.id == id }

    fun nameOf(id: String): String = byId(id)?.name ?: id

    fun isKnown(id: String): Boolean = byId(id) != null
}
