package io.github.stronghorse44.tunnels.ble

/** The cell logbook's line in the Surroundings panel, from the row's state and the last scan's observations. Pure, so the words are tested off-device (CellLogbookTest). */
object CellLogText {
    /** The line when there is no row: the logbook is off until the user starts it. */
    const val OFF = "Cell logbook is off. It learns which towers your phone uses at places you scan often, as keyed hashes only."
    /** The line for a row that cannot be read; only Clear replaces it. */
    const val UNREADABLE = "The logbook could not be read. Clear it to start again."
    /** The line for a row that exists when no scan has judged anything since it was started. */
    const val ON_NO_SCAN = "Cell logbook is on. Scan to start learning."
    const val NO_PLACE = "No place this scan (location is off / no fix / no permission): the logbook did not look."
    const val NO_CELL_ID = "The cell did not report its identity this scan."
    const val RESTARTED = "Relearning: the logbook's key changed."
    const val FAILED = "The logbook could not be updated this scan."

    /** What the row is right now. [places] is how many places it holds. */
    sealed interface Row {
        data object Off : Row
        data object Unreadable : Row
        data class On(val places: Int) : Row
    }

    fun row(raw: String?): Row = when {
        raw == null -> Row.Off
        else -> CellLogbook.decode(raw)?.let { Row.On(it.places.size) } ?: Row.Unreadable
    }

    /** One line: the row first (off and unreadable need no scan), else the last scan's `log:*` observations ([obs] maps key to value). */
    fun line(row: Row, obs: Map<String, String>): String = when (row) {
        Row.Off -> OFF
        Row.Unreadable -> UNREADABLE
        is Row.On -> when (obs[SurroundingsKeys.LOG_STATE]) {
            null, SurroundingsKeys.LOG_OFF -> ON_NO_SCAN
            SurroundingsKeys.LOG_NO_PLACE -> NO_PLACE
            SurroundingsKeys.LOG_NO_CELL_ID -> NO_CELL_ID
            SurroundingsKeys.LOG_UNREADABLE -> UNREADABLE
            SurroundingsKeys.LOG_RESTARTED -> RESTARTED
            else -> placeLine(obs, row.places) ?: if (obs[SurroundingsKeys.LOG_STATE] == SurroundingsKeys.LOG_FAILED) FAILED else ON_NO_SCAN
        }
    }

    private fun placeLine(obs: Map<String, String>, places: Int): String? {
        val scans = obs[SurroundingsKeys.LOG_PLACE_SCANS]?.toIntOrNull() ?: return null
        val towers = obs[SurroundingsKeys.LOG_PLACE_CELLS]?.toIntOrNull() ?: return null
        val tower = if (towers == 1) "1 tower" else "$towers towers"
        // A place is judged from its fifth scan on: after the fourth it already reads as familiar.
        return if (scans < CellLog.FAMILIAR_SCANS) "Learning this place ($scans of ${CellLog.FAMILIAR_SCANS} scans) · $tower"
        else "Familiar place · ${if (towers == 1) "1 tower" else "$towers towers"} known here · ${if (places == 1) "1 place" else "$places places"}"
    }
}
