import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Hard rule #1 helpers: which permissions a module may declare, and which modules may declare INTERNET. */
object Permissions {
    const val INTERNET = "android.permission.INTERNET"

    /**
     * Module directories (relative to the repo root) whose allowlist may contain INTERNET: the Traffic and Home
     * network sessions and the updater's user-started checks and downloads (approved 2026-10-01), and the breach
     * list fetch (approved 2026-10-03, RJ's Q7): one user-started download of the public list from
     * haveibeenpwned.com, held in memory and handed to Linx.
     */
    val internetAllowedIn = setOf("tunnels/traffic", "tunnels/homenet", "tunnels/updater", "tunnels/breaches")

    private val usesPermission = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*?android:name\s*=\s*"([^"]+)"""")

    fun declaredIn(manifest: File): Set<String> =
        if (manifest.isFile) usesPermission.findAll(manifest.readText()).map { it.groupValues[1] }.toSet() else emptySet()

    fun allowlist(file: File): Set<String> =
        if (file.isFile) file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
}

/** Checks one Android library's source manifest against its permissions.allow. */
abstract class VerifyModulePermissionsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) @get:Optional
    abstract val manifest: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) @get:Optional
    abstract val allowFile: RegularFileProperty

    @get:Input
    abstract val modulePath: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val declared = Permissions.declaredIn(manifest.orNull?.asFile ?: File(""))
        val allowed = Permissions.allowlist(allowFile.orNull?.asFile ?: File(""))
        val extra = declared - allowed
        if (extra.isNotEmpty()) {
            throw GradleException("${modulePath.get()} declares permissions not in its permissions.allow: ${extra.sorted().joinToString()}")
        }
        if (Permissions.INTERNET in allowed && modulePath.get() !in Permissions.internetAllowedIn) {
            throw GradleException("${modulePath.get()} may not allow ${Permissions.INTERNET}; only ${Permissions.internetAllowedIn} may.")
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(declared.sorted().joinToString("\n", postfix = "\n"))
    }
}

/** Checks the app's merged manifest against the union of every module allowlist, and writes that union. */
abstract class VerifyAppPermissionsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowFiles: ConfigurableFileCollection

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val unionFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val union = allowFiles.files.flatMap { Permissions.allowlist(it) }.toSet()
        // Permissions the app defines for itself (e.g. the AndroidX dynamic-receiver one) are not egress.
        val declared = Permissions.declaredIn(mergedManifest.get().asFile).filterNot { it.startsWith(applicationId.get()) }.toSet()
        val extra = declared - union
        if (extra.isNotEmpty()) {
            throw GradleException("Merged manifest declares permissions no module allows: ${extra.sorted().joinToString()}")
        }
        unionFile.get().asFile.apply { parentFile.mkdirs() }.writeText(union.sorted().joinToString("\n", postfix = "\n"))
        logger.lifecycle("Permissions OK (${declared.size} declared, ${union.size} allowed): ${declared.sorted().joinToString()}")
    }
}
