package io.github.stronghorse44.tunnels.devicecheck

import android.Manifest
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.Process
import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.runtime.AppLock
import io.github.stronghorse44.tunnels.runtime.RestrictedSettings
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.store.TunnelsStore
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import kotlinx.coroutines.yield
import java.time.Instant

/** Reads the phone for [DeviceChecks]. Every reading swallows system errors; a check then says what it could not read. */
object DeviceCheckRunner {
    const val OTHER_SENSORS = "android.permission.OTHER_SENSORS"

    suspend fun run(context: Context, now: Instant = Instant.now()): List<CheckGroup> {
        val app = context.applicationContext
        val runtime = TunnelsRuntime.get(app)
        val store = runtime.store
        val toggles = toggles(app)
        val grapheneOs = permissionDefined(app, OTHER_SENSORS)

        val silicon = store.dao.latestSnapshotIdFor(SiliconKeys.TUNNEL_ID)?.let { id ->
            val takenAt = store.dao.snapshot(id)?.takenAt?.let(Instant::ofEpochMilli)
            val values = store.dao.observations(id, SiliconKeys.TUNNEL_ID).filter { it.subject == SiliconKeys.SUBJECT }.associate { it.key to it.value }
            values to takenAt
        }

        val settings = WatchSettings.decode(store.setting(WatchSettings.KEY))
        val status = WatchStatus.decode(store.setting(WatchStatus.KEY))
        val scheduled = runCatching { app.getSystemService(JobScheduler::class.java)?.getPendingJob(WatchPolicy.JOB_ID) != null }.getOrDefault(false)
        val notifications = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val unrestricted = runCatching { app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true }.getOrDefault(false)

        val (vpnConnected, vpnOurs) = vpn(app)
        val fromFile = RestrictedSettings.installedFromFile(app)
        val access = runtime.registry.modules.values.sortedBy { it.info.title }.flatMap { module ->
            module.specialAccess.map { a ->
                DeviceChecks.specialAccess(
                    module.id, module.info.title, a.id, a.label,
                    granted = runCatching { a.isGranted() }.getOrDefault(false),
                    restricted = a.restricted,
                    installedFromFile = fromFile,
                )
            }
        }

        return listOf(
            CheckGroup(
                "GrapheneOS readings",
                listOf(
                    DeviceChecks.networkToggle(toggles.network, grapheneOs),
                    DeviceChecks.sensorsToggle(toggles.sensors, grapheneOs),
                    DeviceChecks.verifiedBoot(silicon?.first, silicon?.second, now),
                    DeviceChecks.secondPhone(installed(app, DeviceChecks.AUDITOR), pairedPhones(app)),
                ),
            ),
            CheckGroup(
                "Tunnels itself",
                listOf(
                    DeviceChecks.storeKey(TunnelsStore.keySecurityLevel()),
                    DeviceChecks.packageVisibility(toggles.visible),
                    DeviceChecks.backgroundChecks(settings, status, scheduled, notifications, unrestricted, now),
                    // Asking the biometric service needs USE_BIOMETRIC (declared by the app); never let a probe throw.
                    DeviceChecks.appLock(runCatching { AppLock.canLock(app) }.getOrDefault(false), runCatching { AppLock.isEnabled(app) }.getOrDefault(false)),
                ),
            ),
            CheckGroup("Traffic sessions", listOf(DeviceChecks.vpn(vpnConnected, vpnOurs), DeviceChecks.privateDns(privateDns(app)))),
            CheckGroup("Access you granted", access),
            CheckGroup("By hand", DeviceChecks.byHand),
        ).filter { it.results.isNotEmpty() }
    }

    private class Toggles(val network: ToggleProbe, val sensors: ToggleProbe, val visible: Int)

    /** One pass over every installed app: its Network (INTERNET) and Sensors (OTHER_SENSORS) flags. */
    private suspend fun toggles(context: Context): Toggles {
        val pm = context.packageManager
        val self = context.packageName
        val names = runCatching { pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).map { it.packageName } }.getOrDefault(emptyList())
        var netRequesting = 0
        var netOff = 0
        var netSelf: Boolean? = null
        var senRequesting = 0
        var senOff = 0
        var senSelf: Boolean? = null
        for (name in names) {
            yield()
            val info = runCatching { pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())) }.getOrNull() ?: continue
            val net = flag(info, Manifest.permission.INTERNET)
            val sen = flag(info, OTHER_SENSORS)
            if (name == self) {
                netSelf = net
                senSelf = sen
                continue
            }
            if (net != null) {
                netRequesting++
                if (!net) netOff++
            }
            if (sen != null) {
                senRequesting++
                if (!sen) senOff++
            }
        }
        return Toggles(
            network = ToggleProbe(netSelf, context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED, netRequesting, netOff),
            sensors = ToggleProbe(senSelf, context.checkSelfPermission(OTHER_SENSORS) == PackageManager.PERMISSION_GRANTED, senRequesting, senOff),
            visible = names.size,
        )
    }

    /** The granted bit of [permission] in [info]'s requested permissions, or null when it does not request it. */
    private fun flag(info: PackageInfo, permission: String): Boolean? {
        val requested = info.requestedPermissions ?: return null
        val i = requested.indexOf(permission)
        if (i < 0) return null
        val flags = info.requestedPermissionsFlags ?: return false
        return i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
    }

    /** Phones this one verifies, counted from the pins line by line; the pins themselves are pairing's business. */
    private suspend fun pairedPhones(context: Context): Int = runCatching {
        TunnelsStore.get(context).setting("pairing.pins").orEmpty().lines().count { it.isNotBlank() }
    }.getOrDefault(0)

    private fun installed(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        true
    }.getOrDefault(false)

    private fun permissionDefined(context: Context, permission: String): Boolean =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess

    /** Connected, and whether the VPN is Tunnels' own session (Android reveals a VPN's owner only to the owner). */
    @Suppress("DEPRECATION")
    private fun vpn(context: Context): Pair<Boolean, Boolean> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false to false
        val vpns = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        (vpns.isNotEmpty()) to vpns.any { it.ownerUid == Process.myUid() }
    }.getOrDefault(false to false)

    /** Private DNS of the network a Traffic session forwards to: the best non-VPN network with internet. */
    @Suppress("DEPRECATION")
    private fun privateDns(context: Context): PrivateDns = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PrivateDns.Unknown
        val network: Network = cm.allNetworks
            .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .sortedByDescending { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .firstOrNull()?.first ?: return PrivateDns.Unknown
        val lp = cm.getLinkProperties(network) ?: return PrivateDns.Unknown
        val host = lp.privateDnsServerName
        when {
            host != null -> PrivateDns.Strict(host)
            lp.isPrivateDnsActive -> PrivateDns.Automatic
            else -> PrivateDns.Off
        }
    }.getOrDefault(PrivateDns.Unknown)
}
