package io.github.stronghorse44.tunnels.watchrules

/** `key=value;key=value` for the small records kept in the store's settings table. Values never contain `;` or `=`. */
internal object Codec {
    fun encode(fields: List<Pair<String, String>>): String =
        fields.joinToString(";") { (k, v) -> "$k=${v.replace(';', '_').replace('=', '_')}" }

    fun decode(s: String?): Map<String, String> =
        s.orEmpty().split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }.toMap()

    fun list(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
