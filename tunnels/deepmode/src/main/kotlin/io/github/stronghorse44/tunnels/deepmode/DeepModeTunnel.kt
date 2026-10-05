package io.github.stronghorse44.tunnels.deepmode

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.SpecialAccess
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.posture.PostureKeys
import io.github.stronghorse44.tunnels.posture.PostureObservations
import io.github.stronghorse44.tunnels.posture.PostureParser
import io.github.stronghorse44.tunnels.posture.PostureReader
import io.github.stronghorse44.tunnels.posture.PostureRules
import io.github.stronghorse44.tunnels.posture.PostureTable
import io.github.stronghorse44.tunnels.posture.TableRead
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

/**
 * Deep mode: app-ops history, hidden Settings and the GrapheneOS posture allowlist through Shizuku (shell access
 * the user grants in the Shizuku app). Without Shizuku the scan reports only that deep mode is off and why. With
 * it, every command runs in a Shizuku user service; the text comes back here, is parsed into coarse summaries
 * (modes, day-granular ages, counts) and nothing else is kept.
 */
class DeepModeTunnel(private val context: Context, val shell: ShizukuShell = ShizukuShell(context)) : TunnelModule, TunnelUi {
    override val id: String = DeepKeys.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val specialAccess: List<SpecialAccess> = listOf(
        SpecialAccess(
            id = "shizuku",
            label = "Shizuku",
            reason = SHIZUKU_REASON,
            settingsAction = ShizukuConnectActivity.ACTION,
            isGranted = { ShizukuStatus.cached(context).granted },
        ),
    )

    override val rules: List<FindingRule> = DeepRules.all

    /** Package -> system flag from the latest scan or PackageManager, so "Disable app" is offered only for user apps. */
    private val systemApps = ConcurrentHashMap<String, Boolean>()

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val status = ShizukuStatus.read(context)
        if (!status.granted) return unavailable(status.reason)

        val out = ArrayList<Observation>(512)
        fun add(subject: String, key: String, value: String) = out.add(Observation(id, subject, key, value))
        val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MILLIS
        fun timeLeft() = deadline - SystemClock.elapsedRealtime()

        val targets = targetPackages()
        val imes = inputMethodPackages()
        try {
            shell.withShell { sh ->
                // 1. Hidden settings and posture first, so a long app-ops pass can never starve them of the budget.
                // Each table's output is read once: the watched-settings parser and the posture parser share it.
                progress.report(0, 2, "settings")
                val tables = HashMap<PostureTable, TableRead>()
                for (table in listOf(PostureTable.GLOBAL, PostureTable.SECURE)) {
                    if (timeLeft() < MIN_BATCH_MILLIS) {
                        tables[table] = PostureParser.notRun
                        continue
                    }
                    val text = try {
                        sh.run("settings list ${table.id}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    tables[table] = PostureParser.table(text, PostureKeys.keys(table))
                    if (text == null) continue
                    val values = try {
                        SettingsParser.parseList(text)
                    } catch (_: Exception) {
                        continue
                    }
                    for ((key, value) in values.toSortedMap()) add(DeepKeys.SUBJECT_SETTINGS, DeepKeys.settingKey(table.id, key), value)
                }
                tables[PostureTable.PROPS] = if (timeLeft() < MIN_BATCH_MILLIS) {
                    PostureParser.notRun
                } else {
                    PostureParser.props(
                        try {
                            sh.run(PostureKeys.PROPS_COMMAND)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        },
                    )
                }
                val readings = PostureReader.read(
                    tables.getValue(PostureTable.GLOBAL), tables.getValue(PostureTable.SECURE), tables.getValue(PostureTable.PROPS),
                )
                out += PostureObservations.to(id, readings, tables)

                // 2. App ops, batched so one shell round trip covers several packages.
                val batches = targets.chunked(BATCH_SIZE)
                var scanned = 0
                var omitted = 0
                for ((index, batch) in batches.withIndex()) {
                    progress.report(index * BATCH_SIZE, targets.size, batch.first().packageName)
                    if (timeLeft() < MIN_BATCH_MILLIS) {
                        omitted += batch.size
                        continue
                    }
                    val output = try {
                        sh.run(appOpsCommand(batch.map { it.packageName }))
                    } catch (_: Exception) {
                        omitted += batch.size
                        continue
                    }
                    val perPackage = AppOpsParser.splitBatch(output)
                    for (app in batch) {
                        try {
                            val ops = AppOpsParser.parseGet(perPackage[app.packageName] ?: continue, DeepKeys.TRACKED_OPS)
                            scanned++
                            if (ops.isEmpty()) continue
                            add(app.packageName, DeepKeys.APP_LABEL, app.label)
                            add(app.packageName, DeepKeys.APP_SYSTEM, app.system.toString())
                            if (app.packageName in imes) add(app.packageName, DeepKeys.APP_IME, "true")
                            for (op in DeepKeys.TRACKED_OPS) {
                                val entry = ops[op] ?: continue
                                add(app.packageName, DeepKeys.modeKey(op), entry.mode)
                                add(app.packageName, DeepKeys.lastKey(op), DeepKeys.coarseAge(entry.lastAccessAgo))
                            }
                        } catch (_: Exception) {
                            // One odd package must not sink the scan.
                        }
                    }
                    yield()
                }
                add(DeepKeys.SUBJECT_DEEP, DeepKeys.APPS_SCANNED, scanned.toString())
                if (omitted > 0) add(DeepKeys.SUBJECT_DEEP, DeepKeys.APPS_OMITTED, omitted.toString())

                // 3. Background use of the sensor ops, from the detailed dump (foreground/background is only there).
                val targetNames = targets.mapTo(HashSet()) { it.packageName }
                for ((index, op) in DeepKeys.SENSOR_OPS.withIndex()) {
                    progress.report(index, DeepKeys.SENSOR_OPS.size, "background $op")
                    if (timeLeft() < MIN_BATCH_MILLIS) break
                    val bg = try {
                        AppOpsParser.parseDumpsysBackground(sh.run("dumpsys appops --op $op"))
                    } catch (_: Exception) {
                        continue
                    }
                    for ((pkg, ago) in bg) {
                        if (pkg in targetNames) add(pkg, DeepKeys.bgLastKey(op), DeepKeys.coarseAge(ago))
                    }
                }

                // 4. Apps the user has disabled. The shell lists every disabled package (GrapheneOS ships
                // several the system disabled); PackageManager tells which of those the user did.
                progress.report(1, 2, "disabled apps")
                if (timeLeft() >= MIN_BATCH_MILLIS) {
                    runCatching { SettingsParser.packages(sh.run("cmd package list packages -d --user 0")) }
                        .getOrNull()?.let { add(DeepKeys.SUBJECT_DEEP, DeepKeys.DISABLED_BY_USER, countDisabledByUser(it).toString()) }
                }
            }
        } catch (e: Exception) {
            // Shizuku is granted but the shell could not start: say so instead of failing the whole snapshot.
            return unavailable("${DeepKeys.REASON_SHELL_FAILED}: ${e.message ?: e.javaClass.simpleName}")
        }
        add(DeepKeys.SUBJECT_DEEP, DeepKeys.AVAILABLE, "true")
        add(DeepKeys.SUBJECT_DEEP, DeepKeys.SHIZUKU_VERSION, (status.version ?: 0).toString())
        progress.report(1, 1, "done")
        return out
    }

    private fun unavailable(reason: String): List<Observation> = listOf(
        Observation(id, DeepKeys.SUBJECT_DEEP, DeepKeys.AVAILABLE, "false"),
        Observation(id, DeepKeys.SUBJECT_DEEP, DeepKeys.REASON, reason),
    )

    private class Target(val packageName: String, val label: String, val system: Boolean)

    /** Every user-installed app plus the sensitive system apps that are present, capped and sorted. */
    private fun targetPackages(): List<Target> {
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) }.getOrDefault(emptyList())
        val targets = ArrayList<Target>()
        for (app in apps) {
            if (app.packageName == context.packageName || !ShellRunner.isSafeArgument(app.packageName)) continue
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (system && app.packageName !in SENSITIVE_SYSTEM_APPS) continue
            systemApps[app.packageName] = system
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: app.packageName
            targets += Target(app.packageName, label, system)
        }
        return targets.sortedWith(compareBy<Target>({ it.system }, { it.packageName })).take(MAX_APPS)
    }

    /**
     * How many of [disabled] carry the DISABLED_USER enabled-state (the user turned them off in Settings or
     * through "Disable app"), as opposed to packages the system or a device policy disabled. One
     * PackageManager call per disabled package, capped.
     */
    private fun countDisabledByUser(disabled: List<String>): Int {
        val pm = context.packageManager
        return disabled.asSequence().filter(ShellRunner::isSafeArgument).take(MAX_DISABLED_LOOKUPS).count { pkg ->
            runCatching { pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }.getOrDefault(false)
        }
    }

    /** Packages that provide a keyboard; they read the clipboard by design. Public API, no permission. */
    private fun inputMethodPackages(): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty().mapTo(HashSet()) { it.packageName }
    }.getOrDefault(emptySet())

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        // Cached: this runs for every finding on each emission of the findings flow, on the main thread.
        val granted = ShizukuStatus.cached(context).granted
        return when (draft.kind) {
            DeepRules.DEEP_UNAVAILABLE, PostureRules.POSTURE_UNREAD -> {
                val intent = ShizukuStatus.launchIntent(context) ?: return emptyList()
                listOf(
                    FindingAction.Perform("Open Shizuku") {
                        context.startActivity(intent)
                        "Shizuku opened. If it is not running, start it from Wireless debugging, then grant Tunnels."
                    },
                )
            }
            DeepRules.ADB_ENABLED -> listOf(FindingAction.OpenSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Developer options"))
            DeepRules.SCAN_ALWAYS_ENABLED -> buildList {
                if (granted) add(FindingAction.Perform("Turn scanning off", destructive = true) {
                    runShell("settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0")
                })
                add(FindingAction.OpenSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location settings"))
            }
            DeepRules.LOCK_SCREEN_PRIVATE_CONTENT -> buildList {
                if (granted) add(FindingAction.Perform("Hide private content", destructive = true) {
                    runShell("settings put secure lock_screen_allow_private_notifications 0")
                })
                add(FindingAction.OpenSettings(ACTION_NOTIFICATION_SETTINGS, "Notification settings"))
            }
            DeepRules.ACCESSIBILITY_SERVICE_ON -> listOf(FindingAction.OpenSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility"))
            in PostureRules.kinds -> PostureRules.actionsFor(draft)
            in DeepRules.appKinds -> appActions(draft, granted)
            else -> emptyList()
        }
    }

    private fun appActions(draft: FindingDraft, granted: Boolean): List<FindingAction> {
        val pkg = draft.subject
        if (!ShellRunner.isSafeArgument(pkg)) return listOf(FindingAction.OpenAppDetails(pkg))
        val system = isSystem(pkg)
        if (!granted) return buildList {
            add(FindingAction.OpenAppDetails(pkg))
            if (!system) add(FindingAction.RequestUninstall(pkg))
        }
        val ops = DeepRules.opsMentioned(draft.evidence)
        return buildList {
            if (DeepKeys.RECORD_AUDIO in ops) add(revoke("Revoke microphone", pkg, "android.permission.RECORD_AUDIO"))
            if (DeepKeys.CAMERA in ops) add(revoke("Revoke camera", pkg, "android.permission.CAMERA"))
            if (DeepKeys.FINE_LOCATION in ops) add(
                revoke(
                    "Revoke location", pkg,
                    "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
                ),
            )
            if (DeepKeys.READ_CLIPBOARD in ops) add(
                FindingAction.Perform("Block clipboard reads", destructive = true) { runShell("appops set --user 0 $pkg READ_CLIPBOARD ignore") },
            )
            add(FindingAction.Perform("Revoke network", destructive = true) { runShell("pm revoke --user 0 $pkg android.permission.INTERNET") })
            if (!system) add(FindingAction.Perform("Disable app", destructive = true) { runShell("pm disable-user --user 0 $pkg") })
            // Last: the card shows the first few actions, and the direct ones are what deep mode adds.
            add(FindingAction.OpenAppDetails(pkg))
        }
    }

    /**
     * Revokes each permission with its own `pm revoke`, so one that the app never requested (background
     * location, say) cannot mask the others; the summary names what failed.
     */
    private fun revoke(label: String, pkg: String, vararg permissions: String) = FindingAction.Perform(label, destructive = true) {
        runShellEach(permissions.map { "pm revoke --user 0 $pkg $it" })
    }

    /** Runs one action command through a fresh shell and reduces its output to a line for the snackbar. */
    private suspend fun runShell(command: String): String = runShellEach(listOf(command), ShellRunner::oneLine)

    private suspend fun runShellEach(commands: List<String>, summary: (String) -> String = { ShellRunner.summarise(listOf(it)) }): String =
        withContext(Dispatchers.IO) {
            if (!ShizukuStatus.read(context).granted) return@withContext "Shizuku is not connected; open Deep mode to reconnect."
            val outputs = try {
                shell.withShell { sh -> commands.map { sh.run(it) } }
            } catch (e: Exception) {
                return@withContext e.message ?: "The deep shell did not start."
            }
            if (outputs.size == 1) summary(outputs.single()) else ShellRunner.summarise(outputs)
        }

    private fun isSystem(pkg: String): Boolean = systemApps[pkg] ?: runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(true).also { systemApps[pkg] = it }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        DeepModePanel(state)
    }

    companion object {
        const val SHIZUKU_REASON = "Deep mode reads app-ops history and hidden settings through Shizuku (shell access you grant). Nothing is sent anywhere."

        /** The Settings app's notification screen; the constant is not in the public SDK, the action is handled. */
        const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"

        /** Whole-scan budget; the shell itself caps each command at 20 s. */
        const val SCAN_BUDGET_MILLIS = 50_000L
        private const val MIN_BATCH_MILLIS = 3_000L
        const val BATCH_SIZE = 20
        const val MAX_APPS = 300
        private const val MAX_DISABLED_LOOKUPS = 200

        /** System apps worth checking alongside user apps: they hold the sensors and the data. */
        val SENSITIVE_SYSTEM_APPS: Set<String> = setOf(
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.googlequicksearchbox",
            "com.google.android.as", "com.google.android.apps.messaging", "com.google.android.dialer", "com.android.chrome",
            "com.google.android.inputmethod.latin", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.google.android.apps.nexuslauncher", "com.google.android.apps.wellbeing", "com.google.android.GoogleCamera",
            "com.google.android.apps.photos", "com.google.android.apps.maps", "com.google.android.youtube", "com.google.android.gm",
            "com.android.camera2", "app.vanadium.browser", "app.grapheneos.camera", "app.grapheneos.pdfviewer", "app.grapheneos.apps",
            "app.grapheneos.gmscompat", "moe.shizuku.privileged.api",
        )

        /** `appops get` for several packages in one shell, each preceded by a marker line. */
        fun appOpsCommand(packages: List<String>): String =
            "for p in ${packages.joinToString(" ")}; do echo \"${AppOpsParser.PACKAGE_MARKER}\$p\"; appops get --user 0 \"\$p\" 2>&1; done"
    }
}
