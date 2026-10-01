package io.github.stronghorse44.tunnels.attestation

import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Patch levels as the attestation record encodes them: YYYYMM for the OS, YYYYMMDD for vendor and boot. */
object PatchLevel {
    /** 202505 -> 2025-05-01, 20250505 -> 2025-05-05; null for 0, negative or impossible dates. */
    fun parse(level: Int): LocalDate? {
        if (level <= 0) return null
        return try {
            when {
                level in 190001..999912 -> LocalDate.of(level / 100, level % 100, 1)
                level in 19000101..99991231 -> LocalDate.of(level / 10000, (level / 100) % 100, level % 100)
                else -> null
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Accepts "202505", "20250505" and Android's `Build.VERSION.SECURITY_PATCH` form "2025-05-05". */
    fun parse(text: String?): LocalDate? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length == 10 && t[4] == '-' && t[7] == '-') {
            return try {
                LocalDate.parse(t)
            } catch (_: DateTimeException) {
                null
            }
        }
        return t.toIntOrNull()?.let(::parse)
    }

    /** Whole days from the patch level's date to [today]; negative when the level lies in the future. */
    fun ageDays(level: Int, today: LocalDate): Long? = parse(level)?.let { ChronoUnit.DAYS.between(it, today) }

    /** "2025-05" for a monthly level, "2025-05-05" for a daily one, or the raw number if it does not parse. */
    fun format(level: Int): String {
        val date = parse(level) ?: return level.toString()
        return if (level < 10000000) "%04d-%02d".format(date.year, date.monthValue) else date.toString()
    }

    /** "6 weeks" / "3 months" / "today", for evidence strings. */
    fun describeAge(days: Long): String = when {
        days <= 0 -> "current"
        days < 14 -> "$days days old"
        days < 60 -> "${days / 7} weeks old"
        days < 365 -> "${days / 30} months old"
        else -> "over ${days / 365} year${if (days / 365 == 1L) "" else "s"} old"
    }
}
