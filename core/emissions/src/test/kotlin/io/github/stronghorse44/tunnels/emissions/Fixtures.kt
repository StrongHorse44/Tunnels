package io.github.stronghorse44.tunnels.emissions

/** A reading with every transmitter [default] and [states] overriding it; sources are the app's, all confirmed. */
fun reading(
    default: EmitterState = EmitterState.OFF,
    states: Map<Emitter, EmitterState> = emptyMap(),
    airplane: Boolean? = false,
    shell: ShellAccess = ShellAccess.NOT_RUNNING,
    unconfirmed: Set<Emitter> = emptySet(),
    hotspotKinds: List<String> = emptyList(),
    location: Boolean? = true,
    appUse: AppUseCounts? = null,
): EmissionsReading = EmissionsReading(
    emitters = Emitter.entries.associateWith { e ->
        EmitterReading(e, states[e] ?: default, ReadSource.APP, confirmed = e !in unconfirmed)
    },
    airplane = airplane,
    sim = "present",
    mobileData = true,
    location = location,
    hotspotKinds = hotspotKinds,
    shell = shell,
    appUse = appUse,
)
