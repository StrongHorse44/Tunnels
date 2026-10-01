package io.github.stronghorse44.tunnels.elf

import io.github.stronghorse44.tunnels.model.Observation

/** Observation key schema of the hardening tunnel. Subject is always the package name. */
object HardeningKeys {
    const val TUNNEL_ID = "hardening"

    const val LABEL = "app:label"
    /** "true" for system (preinstalled) apps, "false" otherwise. */
    const val SYSTEM = "app:system"
    /** Number of lib/<abi>/<file>.so entries across base and split APKs. */
    const val LIBS = "hardening:libs"
    /** Comma list of lib/<abi> directories holding native code, or "none". */
    const val ABIS = "hardening:abis"
    /** "true" when a 64-bit ABI directory exists, "false" when only 32-bit code ships, "none" without native code. */
    const val BITS_64 = "hardening:64bit"
    /** Parsed libraries lacking NX or PIE. */
    const val WEAK = "hardening:weak"
    /** Parsed libraries lacking BIND_NOW (lazy binding, so RELRO is at best partial). */
    const val PARTIAL_RELRO = "hardening:partialRelro"
    /** Parsed libraries without a stack canary import. */
    const val NO_CANARY = "hardening:noCanary"
    /** Present ("true") only when more libraries than the cap exist; the rest were not read. */
    const val TRUNCATED = "hardening:truncated"
    /** Present only when libraries were skipped for size; value is the count. */
    const val SKIPPED = "hardening:skipped"
    /** Present only when some libraries were not valid ELF; value is the count. */
    const val UNPARSED = "hardening:unparsed"
    /** `lib:<abi>/<file>.so` = [HardeningReport.flags], or [UNPARSED_VALUE]. */
    const val LIB_PREFIX = "lib:"
    /** Emitted instead of the normal keys when one app's inspection threw. */
    const val ERROR = "scan:error"

    const val NONE = "none"
    const val UNPARSED_VALUE = "unreadable"

    fun libKey(abi: String, name: String) = "$LIB_PREFIX$abi/$name"

    fun isLibKey(key: String) = key.startsWith(LIB_PREFIX)

    /** `lib:arm64-v8a/libfoo.so` to `libfoo.so`. */
    fun libName(key: String): String = key.removePrefix(LIB_PREFIX).substringAfterLast('/')

    /** `lib:arm64-v8a/libfoo.so` to `arm64-v8a`, or "" when the key carries no ABI. */
    fun libAbi(key: String): String = key.removePrefix(LIB_PREFIX).substringBefore('/', "")

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun count(obs: List<Observation>, key: String): Int = value(obs, key)?.toIntOrNull() ?: 0

    fun isSystem(obs: List<Observation>) = value(obs, SYSTEM) == "true"

    /** Parsed libraries of one subject: library file key to its report, in key order. */
    fun libs(obs: List<Observation>): List<Pair<String, HardeningReport>> =
        obs.filter { isLibKey(it.key) }.sortedBy { it.key }.mapNotNull { o ->
            val is64 = libAbi(o.key) in NativeLibs.ABIS_64
            HardeningReport.fromFlags(o.value, is64Bit = is64)?.let { o.key to it }
        }

    /** "libfoo.so, libbar.so and 2 more" for up to [max] names. */
    fun nameList(names: List<String>, max: Int = 3): String {
        val shown = names.take(max).joinToString(", ")
        val rest = names.size - max
        return if (rest > 0) "$shown and $rest more" else shown
    }
}
