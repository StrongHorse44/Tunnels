package io.github.stronghorse44.tunnels.traffic

import android.content.Context
import android.util.Log
import io.github.stronghorse44.tunnels.dns.Blocklists
import io.github.stronghorse44.tunnels.dns.HostList
import java.util.concurrent.ConcurrentHashMap

/** The blocklists shipped in assets ([Blocklists]), read once per process and kept as compact [HostList]s. */
object BundledLists {
    private const val TAG = "TunnelsLists"
    private val loaded = ConcurrentHashMap<String, HostList>()

    /** The list [id], read from assets on first use; empty if the asset is missing or unreadable. */
    fun get(context: Context, id: String): HostList = loaded.getOrPut(id) {
        val list = Blocklists.byId(id) ?: return@getOrPut HostList.EMPTY
        try {
            context.applicationContext.assets.open(list.asset).bufferedReader().useLines { HostList.parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "list $id not read: ${e.javaClass.simpleName}")
            HostList.EMPTY
        }
    }

    /** The first of [ids] whose list holds [host], or null. */
    fun listed(context: Context, host: String, ids: Set<String>): String? = ids.firstOrNull { host in get(context, it) }
}
