package io.github.stronghorse44.tunnels.elf

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the hardening tunnel. Pure functions over observations, unit-tested here. */
object HardeningRules {
    const val WEAK_HARDENING = "WEAK_HARDENING"
    const val PARTIAL_RELRO = "PARTIAL_RELRO"
    const val NO_CANARY = "NO_CANARY"
    const val LEGACY_32BIT = "LEGACY_32BIT"
    const val HARDENING_REGRESSED = "HARDENING_REGRESSED"

    private const val NAMED_LIBS = 3

    /** State WARN: native libraries without an executable-stack guard or position independence. */
    val weakHardening: FindingRule = Rules.perSubject(WEAK_HARDENING, Severity.WARN) { _, obs ->
        val libs = HardeningKeys.libs(obs)
        val weak = libs.filter { it.second.weak }
        if (weak.isEmpty()) return@perSubject null
        val named = weak.take(NAMED_LIBS).joinToString(", ") { (key, report) ->
            "${HardeningKeys.libName(key)} (no ${report.missing.filter { it == "PIE" || it == "NX stack" }.joinToString(", no ")})"
        }
        val rest = weak.size - NAMED_LIBS
        val list = if (rest > 0) "$named and $rest more" else named
        "${weak.size} of ${libs.size} native libraries lack basic exploit mitigations: $list. " +
            "A bug in such code is far easier to turn into a takeover of the app."
    }

    /** State NOTICE: libraries bound lazily, leaving their function tables writable while the app runs. */
    val partialRelro: FindingRule = Rules.perSubject(PARTIAL_RELRO, Severity.NOTICE) { _, obs ->
        val libs = HardeningKeys.libs(obs)
        val lazy = libs.filter { !it.second.bindNow }
        if (lazy.isEmpty()) return@perSubject null
        val names = HardeningKeys.nameList(lazy.map { HardeningKeys.libName(it.first) }, NAMED_LIBS)
        "${lazy.size} of ${libs.size} native libraries use lazy binding (partial RELRO): $names. " +
            "Their function pointer tables stay writable while the app runs."
    }

    /** State INFO: libraries that import no stack-canary handler. */
    val noCanary: FindingRule = Rules.perSubject(NO_CANARY, Severity.INFO) { _, obs ->
        val libs = HardeningKeys.libs(obs)
        val bare = libs.filter { !it.second.canary }
        if (bare.isEmpty()) return@perSubject null
        val names = HardeningKeys.nameList(bare.map { HardeningKeys.libName(it.first) }, NAMED_LIBS)
        "${bare.size} of ${libs.size} native libraries have no stack canary: $names. " +
            "Small libraries without stack buffers legitimately have none; large ones should."
    }

    /** State WARN: native code shipped only for 32-bit CPUs, which 64-bit-only phones cannot run. */
    val legacy32Bit: FindingRule = Rules.perSubject(LEGACY_32BIT, Severity.WARN) { _, obs ->
        if (HardeningKeys.value(obs, HardeningKeys.BITS_64) != "false") return@perSubject null
        val abis = HardeningKeys.value(obs, HardeningKeys.ABIS) ?: "32-bit only"
        "Ships only 32-bit native code ($abis). 64-bit-only phones such as the Pixel 10 cannot run it, and " +
            "the code predates a decade of toolchain hardening."
    }

    /** Sticky WARN: an update raised the number of weakly hardened libraries. */
    val hardeningRegressed: FindingRule = Rules.onChange(HARDENING_REGRESSED, Severity.WARN) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        if (c.key.key != HardeningKeys.WEAK) return@onChange null
        val before = c.before.value.toIntOrNull() ?: return@onChange null
        val after = c.after.value.toIntOrNull() ?: return@onChange null
        if (after <= before) return@onChange null
        "An update raised the number of native libraries lacking basic mitigations from $before to $after."
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(weakHardening, partialRelro, noCanary, legacy32Bit, hardeningRegressed)
}
