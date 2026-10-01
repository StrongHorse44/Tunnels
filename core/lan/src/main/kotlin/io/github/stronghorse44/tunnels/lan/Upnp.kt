package io.github.stronghorse44.tunnels.lan

/** What a UPnP device description XML says about the device, bounded and string-scanned. */
data class UpnpDescription(
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val deviceTypes: List<String>,
    val serviceTypes: List<String>,
) {
    /** The device offers port mapping: a WANIPConnection or WANPPPConnection service. */
    val hasIgd: Boolean get() = serviceTypes.any(Upnp::isIgdService)

    /** "FRITZ!Box 7590 (AVM)" style label for the UI, or null when nothing was named. */
    val label: String?
        get() {
            val name = friendlyName?.takeIf { it.isNotBlank() } ?: modelName?.takeIf { it.isNotBlank() } ?: return manufacturer?.takeIf { it.isNotBlank() }
            val maker = manufacturer?.takeIf { it.isNotBlank() && !name.contains(it, true) }
            return if (maker != null) "$name ($maker)" else name
        }

    companion object {
        val EMPTY = UpnpDescription(null, null, null, emptyList(), emptyList())
    }
}

/** Minimal, regex-based scan of a UPnP description. Never builds a DOM; input is capped at [MAX_CHARS]. */
object Upnp {
    const val MAX_CHARS = 64 * 1024
    private const val MAX_TYPES = 32
    private const val MAX_TEXT = 120

    private val serviceType = Regex("<serviceType[^>]*>\\s*([^<\\s]{1,200})\\s*</serviceType>", RegexOption.IGNORE_CASE)
    private val deviceType = Regex("<deviceType[^>]*>\\s*([^<\\s]{1,200})\\s*</deviceType>", RegexOption.IGNORE_CASE)
    private val friendlyName = Regex("<friendlyName[^>]*>([^<]{1,200})</friendlyName>", RegexOption.IGNORE_CASE)
    private val manufacturer = Regex("<manufacturer[^>]*>([^<]{1,200})</manufacturer>", RegexOption.IGNORE_CASE)
    private val modelName = Regex("<modelName[^>]*>([^<]{1,200})</modelName>", RegexOption.IGNORE_CASE)

    fun scan(xml: String): UpnpDescription {
        val text = if (xml.length > MAX_CHARS) xml.substring(0, MAX_CHARS) else xml
        return UpnpDescription(
            friendlyName = friendlyName.find(text)?.groupValues?.get(1)?.let(::clean),
            manufacturer = manufacturer.find(text)?.groupValues?.get(1)?.let(::clean),
            modelName = modelName.find(text)?.groupValues?.get(1)?.let(::clean),
            deviceTypes = deviceType.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_TYPES).toList(),
            serviceTypes = serviceType.findAll(text).map { it.groupValues[1] }.distinct().take(MAX_TYPES).toList(),
        )
    }

    fun isIgdService(type: String): Boolean = type.contains("WANIPConnection", true) || type.contains("WANPPPConnection", true)

    private fun clean(s: String): String? = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").trim().take(MAX_TEXT).ifEmpty { null }
}
