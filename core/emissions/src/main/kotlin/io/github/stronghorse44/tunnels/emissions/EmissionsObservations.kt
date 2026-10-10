package io.github.stronghorse44.tunnels.emissions

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation schema of the emissions tunnel. Subjects: one per [Emitter] (`state`, `source`, `confirmed`; cellular
 * adds `airplane`, `sim` and `mobileData`, hotspot adds `kinds`), [LOCATION] (`state`, never an emission), [SUMMARY]
 * (counts, the quiet level, the shell) and [APPS] (how many apps used each radio recently, never which). Summaries
 * only: no identifier, address, package name or time.
 */
object EmissionsKeys {
    const val TUNNEL_ID = "emissions"

    const val LOCATION = "location"
    const val SUMMARY = "summary"
    const val APPS = "apps"

    const val STATE = "state"
    const val SOURCE = "source"
    const val CONFIRMED = "confirmed"
    const val AIRPLANE = "airplane"
    const val SIM = "sim"
    const val MOBILE_DATA = "mobileData"
    const val KINDS = "kinds"
    const val EMITS = "emits"

    const val ON_COUNT = "on"
    const val OFF_COUNT = "off"
    const val UNKNOWN_COUNT = "unknown"
    const val ABSENT_COUNT = "absent"
    const val LEVEL = "level"
    const val SHELL = "shell"

    const val APPS_READ = "read"
    const val READ_YES = "yes"
    const val READ_NO_SHELL = "no shell"
    const val READ_FAILED = "failed"

    /** The settings row that remembers what was on before Silence, for Restore. Not exported. */
    const val RESTORE_SETTING = "emissions.restore"

    fun onOff(value: Boolean?): String = when (value) {
        true -> EmitterState.ON.id
        false -> EmitterState.OFF.id
        null -> EmitterState.UNKNOWN.id
    }

    fun flag(value: String?): Boolean? = when (value) {
        EmitterState.ON.id -> true
        EmitterState.OFF.id -> false
        else -> null
    }
}

/** [EmissionsReading] to observations and back; the rules and the screen read the stored form. */
object EmissionsObservations {
    fun toObservations(r: EmissionsReading, tunnelId: String = EmissionsKeys.TUNNEL_ID): List<Observation> {
        val out = ArrayList<Observation>()
        fun add(subject: String, key: String, value: String) {
            out += Observation(tunnelId, subject, key, value)
        }
        for (e in Emitter.entries) {
            val reading = r.reading(e)
            add(e.id, EmissionsKeys.STATE, reading.state.id)
            reading.source?.let { add(e.id, EmissionsKeys.SOURCE, it.id) }
            add(e.id, EmissionsKeys.CONFIRMED, reading.confirmed.toString())
        }
        add(Emitter.CELLULAR.id, EmissionsKeys.AIRPLANE, EmissionsKeys.onOff(r.airplane))
        r.sim?.let { add(Emitter.CELLULAR.id, EmissionsKeys.SIM, it) }
        add(Emitter.CELLULAR.id, EmissionsKeys.MOBILE_DATA, EmissionsKeys.onOff(r.mobileData))
        if (r.hotspotKinds.isNotEmpty()) add(Emitter.HOTSPOT.id, EmissionsKeys.KINDS, r.hotspotKinds.sorted().joinToString(","))

        add(EmissionsKeys.LOCATION, EmissionsKeys.STATE, EmissionsKeys.onOff(r.location))
        add(EmissionsKeys.LOCATION, EmissionsKeys.EMITS, "no")

        add(EmissionsKeys.SUMMARY, EmissionsKeys.ON_COUNT, r.on.size.toString())
        add(EmissionsKeys.SUMMARY, EmissionsKeys.OFF_COUNT, r.off.size.toString())
        add(EmissionsKeys.SUMMARY, EmissionsKeys.UNKNOWN_COUNT, r.unknown.size.toString())
        add(EmissionsKeys.SUMMARY, EmissionsKeys.ABSENT_COUNT, (Emitter.entries.size - r.present.size).toString())
        add(EmissionsKeys.SUMMARY, EmissionsKeys.LEVEL, r.level.id)
        add(EmissionsKeys.SUMMARY, EmissionsKeys.SHELL, r.shell.id)

        val use = r.appUse
        when {
            use == null -> add(EmissionsKeys.APPS, EmissionsKeys.APPS_READ, EmissionsKeys.READ_NO_SHELL)
            use.failed -> add(EmissionsKeys.APPS, EmissionsKeys.APPS_READ, EmissionsKeys.READ_FAILED)
            else -> {
                add(EmissionsKeys.APPS, EmissionsKeys.APPS_READ, EmissionsKeys.READ_YES)
                RadioUse.Kind.entries.forEach { k -> add(EmissionsKeys.APPS, k.id, (use.counts[k] ?: 0).toString()) }
            }
        }
        return out
    }

    /** The reading the observations describe; anything missing reads as unknown. */
    fun from(observations: List<Observation>): EmissionsReading {
        val by = observations.groupBy { it.subject }.mapValues { (_, obs) -> obs.associate { it.key to it.value } }
        val emitters = Emitter.entries.associateWith { e ->
            val facts = by[e.id].orEmpty()
            EmitterReading(
                e,
                EmitterState.byId(facts[EmissionsKeys.STATE]),
                ReadSource.byId(facts[EmissionsKeys.SOURCE]),
                facts[EmissionsKeys.CONFIRMED] != "false",
            )
        }
        val cell = by[Emitter.CELLULAR.id].orEmpty()
        val apps = by[EmissionsKeys.APPS].orEmpty()
        val appUse = when (apps[EmissionsKeys.APPS_READ]) {
            EmissionsKeys.READ_YES -> AppUseCounts(RadioUse.Kind.entries.associateWith { apps[it.id]?.toIntOrNull() ?: 0 })
            EmissionsKeys.READ_FAILED -> AppUseCounts(emptyMap(), failed = true)
            else -> null
        }
        return EmissionsReading(
            emitters = emitters,
            airplane = EmissionsKeys.flag(cell[EmissionsKeys.AIRPLANE]),
            sim = cell[EmissionsKeys.SIM],
            mobileData = EmissionsKeys.flag(cell[EmissionsKeys.MOBILE_DATA]),
            location = EmissionsKeys.flag(by[EmissionsKeys.LOCATION]?.get(EmissionsKeys.STATE)),
            hotspotKinds = by[Emitter.HOTSPOT.id]?.get(EmissionsKeys.KINDS)?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            shell = ShellAccess.byId(by[EmissionsKeys.SUMMARY]?.get(EmissionsKeys.SHELL)),
            appUse = appUse,
        )
    }
}

/** The Restore memory: ids of the transmitters that were on when Silence ran, comma-separated. */
object RestoreSet {
    fun encode(emitters: Collection<Emitter>): String? =
        emitters.filter { it.restorable }.distinct().sortedBy { it.ordinal }.joinToString(",") { it.id }.ifEmpty { null }

    fun decode(value: String?): Set<Emitter> =
        value.orEmpty().split(',').mapNotNull { Emitter.byId(it.trim()) }.filter { it.restorable }.toSortedSet(compareBy { it.ordinal })

    /** What to remember before silencing: what is on now, plus anything a Silence earlier left to restore. */
    fun merge(existing: String?, nowOn: Collection<Emitter>): String? = encode(decode(existing) + nowOn)
}
