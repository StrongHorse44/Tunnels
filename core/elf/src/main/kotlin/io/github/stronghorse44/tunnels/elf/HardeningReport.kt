package io.github.stronghorse44.tunnels.elf

/**
 * Exploit mitigations one ELF file was built with. A report with a non-null [parseError] carries no
 * usable flags: the file was not an ELF image, or was too malformed to read.
 */
data class HardeningReport(
    /** arm64, arm, x86_64, x86, riscv64 or other. */
    val arch: String,
    val is64Bit: Boolean,
    /** Position independent (ET_DYN: a shared object or PIE executable), so the loader can place it anywhere. */
    val pie: Boolean,
    /** Non-executable stack: PT_GNU_STACK present and without PF_X. */
    val nx: Boolean,
    /** PT_GNU_RELRO present: relocated data is made read-only after loading. */
    val relro: Boolean,
    /** All symbols are bound at load time (DT_BIND_NOW, DF_BIND_NOW or DF_1_NOW), making RELRO full. */
    val bindNow: Boolean,
    /** Imports `__stack_chk_fail`, so at least one function is protected by a stack canary. */
    val canary: Boolean,
    /** Imports a `__*_chk` function other than the canary handler (fortified libc calls). */
    val fortify: Boolean,
    /** No static symbol table (.symtab) or no section headers at all. */
    val stripped: Boolean,
    val parseError: String? = null,
) {
    val fullRelro: Boolean get() = relro && bindNow

    /** Lacks a mitigation every modern toolchain emits by default. */
    val weak: Boolean get() = !nx || !pie

    /** "pie,nx,relro,bindnow,canary,fortify", each prefixed by "!" when missing. */
    val flags: String
        get() = listOf(PIE to pie, NX to nx, RELRO to relro, BINDNOW to bindNow, CANARY to canary, FORTIFY to fortify)
            .joinToString(",") { (name, on) -> if (on) name else "!$name" }

    /** The missing mitigations as readable words, e.g. ["PIE", "NX stack"]. */
    val missing: List<String>
        get() = buildList {
            if (!pie) add("PIE")
            if (!nx) add("NX stack")
            if (!relro) add("RELRO")
            if (!bindNow) add("BIND_NOW")
            if (!canary) add("stack canary")
            if (!fortify) add("FORTIFY")
        }

    companion object {
        const val PIE = "pie"
        const val NX = "nx"
        const val RELRO = "relro"
        const val BINDNOW = "bindnow"
        const val CANARY = "canary"
        const val FORTIFY = "fortify"

        private val FLAG_NAMES = listOf(PIE, NX, RELRO, BINDNOW, CANARY, FORTIFY)

        fun failed(error: String): HardeningReport =
            HardeningReport("other", false, false, false, false, false, false, false, true, parseError = error)

        /** Parses a [flags] string back into a report (arch and stripping unknown); null when it is not one. */
        fun fromFlags(value: String, arch: String = "other", is64Bit: Boolean = false): HardeningReport? {
            val tokens = value.split(',')
            if (tokens.size != FLAG_NAMES.size) return null
            val on = BooleanArray(FLAG_NAMES.size)
            for ((i, token) in tokens.withIndex()) {
                val name = token.removePrefix("!")
                if (name != FLAG_NAMES[i]) return null
                on[i] = !token.startsWith("!")
            }
            return HardeningReport(arch, is64Bit, on[0], on[1], on[2], on[3], on[4], on[5], stripped = true)
        }
    }
}
