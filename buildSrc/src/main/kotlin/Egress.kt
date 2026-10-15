import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Hard rule #1 in the code, not only in the manifests (audit M2). INTERNET belongs to the whole app, so a module that
 * declares no permission can still open a socket once any module may; and Deep mode's Shizuku shell runs as the shell
 * user, outside the GrapheneOS Network toggle. This check reads every module's compiled classes and refuses any
 * reference to a network or shell API that `buildSrc/egress.allow` does not grant to that module.
 *
 * What counts as a reference: a class named by an instruction, a field or method descriptor, a generic signature, an
 * annotation, a catch clause, the class header or anywhere else in the constant pool; a field or method of that class
 * called or read; and a string constant spelling such a class's name (reflection).
 */
object Egress {
    const val ALLOW_FILE = "buildSrc/egress.allow"

    enum class Category(val word: String) { NETWORK("network"), SHELL("shell") }

    sealed class Rule(val category: Category, val what: String) {
        /** Refused wherever the type is named. */
        class Type(category: Category, what: String, val matches: (String) -> Boolean) : Rule(category, what)

        /** The type is a plain value; only these of its members reach the network or a shell. */
        class Member(category: Category, what: String, val owners: Set<String>, val names: Set<String>) : Rule(category, what)
    }

    private fun classOrNested(name: String): (String) -> Boolean = { it == name || it.startsWith("$name$") }
    private fun under(vararg prefixes: String): (String) -> Boolean = { t -> prefixes.any { t.startsWith(it) } }

    /**
     * java.net types that hold or parse values and open nothing: URI and its exception, the URL form codecs, IDN, and
     * the address classes (whose name lookups and reachability probes are refused below, by member).
     */
    val javaNetValueTypes = setOf(
        "java/net/URI", "java/net/URISyntaxException", "java/net/URLEncoder", "java/net/URLDecoder", "java/net/IDN",
        "java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address",
    )

    private const val DEEPMODE = "io/github/stronghorse44/tunnels/deepmode/"

    val rules: List<Rule> = listOf(
        Rule.Type(Category.NETWORK, "java.net sockets, URLs and connections") {
            it.startsWith("java/net/") && it !in javaNetValueTypes
        },
        Rule.Member(
            Category.NETWORK, "host name lookups and reachability probes",
            setOf("java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address"),
            setOf("getByName", "getAllByName", "getLocalHost", "isReachable", "getHostName", "getCanonicalHostName"),
        ),
        Rule.Type(Category.NETWORK, "javax.net socket factories and TLS", under("javax/net/")),
        Rule.Type(Category.NETWORK, "NIO socket channels") {
            it.startsWith("java/nio/channels/") && it.substringAfterLast('/').let { simple ->
                "Socket" in simple || listOf("DatagramChannel", "NetworkChannel", "MulticastChannel").any { c -> simple.startsWith(c) }
            }
        },
        Rule.Type(Category.NETWORK, "JDK-internal networking", under("sun/net/", "jdk/net/", "com/sun/net/")),
        Rule.Member(
            Category.NETWORK, "sockets, connections and lookups on an Android Network",
            setOf("android/net/Network"), setOf("bindSocket", "openConnection", "getSocketFactory", "getAllByName", "getByName"),
        ),
        Rule.Type(Category.NETWORK, "Android HTTP stack", under("android/net/http/")),
        Rule.Type(Category.NETWORK, "network service discovery", under("android/net/nsd/")),
        Rule.Type(Category.NETWORK, "VPN service", classOrNested("android/net/VpnService")),
        Rule.Type(Category.NETWORK, "DNS queries", classOrNested("android/net/DnsResolver")),
        Rule.Type(Category.NETWORK, "socket factory", classOrNested("android/net/SSLCertificateSocketFactory")),
        Rule.Type(Category.NETWORK, "Wi-Fi Aware and Wi-Fi Direct connections", under("android/net/wifi/aware/", "android/net/wifi/p2p/")),
        Rule.Member(
            Category.NETWORK, "raw sockets through android.system.Os",
            setOf("android/system/Os"), setOf("socket", "connect", "bind", "listen", "accept", "sendto", "sendmsg", "recvfrom", "recvmsg"),
        ),
        Rule.Type(Category.NETWORK, "local sockets (netd and dnsproxyd, a DNS path)", under("android/net/LocalSocket")),
        Rule.Type(Category.NETWORK, "RTP streams", under("android/net/rtp/")),
        Rule.Member(
            Category.NETWORK, "media fetched from a URL in-process",
            setOf("android/media/MediaPlayer", "android/media/MediaExtractor", "android/media/MediaMetadataRetriever", "android/widget/VideoView"),
            setOf("setDataSource", "setVideoURI", "setVideoPath"),
        ),
        Rule.Member(Category.NETWORK, "media fetched from a URL in-process", setOf("android/media/MediaPlayer"), setOf("create")),
        Rule.Type(Category.NETWORK, "DownloadManager", classOrNested("android/app/DownloadManager")),
        Rule.Type(Category.NETWORK, "WebView", under("android/webkit/WebView")),
        Rule.Type(
            Category.NETWORK, "HTTP client libraries",
            under("okhttp3/", "com/squareup/okhttp/", "retrofit2/", "io/ktor/", "org/apache/http/", "com/android/volley/", "org/chromium/net/"),
        ),
        Rule.Type(Category.SHELL, "Deep mode's Shizuku shell (shell uid, outside the Network toggle)") { t ->
            listOf("ShizukuShell", "IDeepShell", "DeepShellService", "ShellRunner").any { classOrNested(DEEPMODE + it)(t) }
        },
        Rule.Type(Category.SHELL, "the Shizuku API", under("rikka/shizuku/", "moe/shizuku/")),
        Rule.Type(Category.SHELL, "child processes", classOrNested("java/lang/ProcessBuilder")),
        Rule.Member(Category.SHELL, "child processes", setOf("java/lang/Runtime"), setOf("exec")),
        Rule.Member(Category.SHELL, "child processes", setOf("android/system/Os"), setOf("execv", "execve")),
    )

    /** One refused-unless-allowed reference found in a class. */
    data class Finding(
        /** The class that holds the reference, internal form. */
        val inClass: String,
        /** Where in that class: "method open()V", "field socket", "class header", "constant pool". */
        val location: String,
        /** The network or shell type named. */
        val type: String,
        /** The field or method reference it came through ("java/net/Socket.<init> (Ljava/lang/String;I)V"), if any. */
        val via: String?,
        /** For a [Rule.Member] finding, the member's name (allowlist key `type.name`). */
        val memberName: String?,
        val rule: Rule,
    ) {
        fun describe(): String = buildString {
            append(inClass.replace('/', '.')).append(", ").append(location).append(": ").append(type)
            if (memberName != null) append('.').append(memberName)
            if (via != null) append(" (via ").append(via).append(')')
            append(" [").append(rule.category.word).append(": ").append(rule.what).append(']')
        }
    }

    /**
     * A string that reads as a class name: dotted or slashed segments, the last one starting with a capital
     * ("java.net.Socket"), so a property name such as "java.net.preferIPv4Stack" or a package name is not one.
     */
    private val classNameShape = Regex("""^([A-Za-z_$][\w$]*[./])+[A-Z][\w$]*$""")

    fun typeRuleFor(type: String): Rule.Type? = rules.filterIsInstance<Rule.Type>().firstOrNull { it.matches(type) }

    fun memberRuleFor(owner: String, name: String): Rule.Member? =
        rules.filterIsInstance<Rule.Member>().firstOrNull { owner in it.owners && name in it.names }

    /** Every refused-unless-allowed reference in one class file. */
    fun scan(classBytes: ByteArray): List<Finding> {
        val parsed = ClassFileReader.parse(classBytes)
        val findings = LinkedHashSet<Finding>()
        for (ref in parsed.refs) {
            val via = ref.member?.let { if (ref.owner.isEmpty()) "invokedynamic $it" else "${ref.owner}.$it" }
            if (ref.string != null) {
                if (classNameShape.matches(ref.string)) {
                    val type = ref.string.replace('.', '/')
                    typeRuleFor(type)?.let { findings += Finding(parsed.name, ref.location, type, "string constant \"${ref.string}\"", null, it) }
                }
                continue
            }
            if (ref.owner.isNotEmpty()) typeRuleFor(ref.owner)?.let { findings += Finding(parsed.name, ref.location, ref.owner, via, null, it) }
            ref.descriptorTypes.forEach { t -> typeRuleFor(t)?.let { findings += Finding(parsed.name, ref.location, t, via, null, it) } }
            val name = ref.member?.substringBefore(' ')
            if (name != null) memberRuleFor(ref.owner, name)?.let { findings += Finding(parsed.name, ref.location, ref.owner, via, name, it) }
        }
        // The constant pool sweep repeats what the walk already placed; keep it only for what nothing else located.
        val located = findings.filter { it.location != "constant pool" }.map { Triple(it.type, it.via, it.memberName) }.toSet()
        return findings.filterNot { it.location == "constant pool" && Triple(it.type, it.via, it.memberName) in located }
    }

    /**
     * `buildSrc/egress.allow`: one grant per line, `<module dir> <pattern>  # why`. A pattern is `@network` or `@shell`
     * (every rule of that category), a type in internal form (`java/net/Socket`, every reference to it), a prefix ending
     * in `*` (`java/net/` followed by `*`), or `type.member` for one member a [Rule.Member] refuses (`java/net/InetAddress.getByName`).
     */
    class Allowlist(val grants: List<Grant>) {
        data class Grant(val module: String, val pattern: String, val line: Int, val category: Category)

        fun allows(module: String, finding: Finding): Boolean = grantFor(module, finding) != null

        fun grantFor(module: String, finding: Finding): Grant? = grants.firstOrNull { it.module == module && matches(it.pattern, finding) }

        fun grantsFor(module: String): List<Grant> = grants.filter { it.module == module }

        companion object {
            private val patternShape = Regex("""^(@network|@shell|[A-Za-z0-9_$/]+(\*|\.[A-Za-z0-9_$<>]+)?)$""")

            fun matches(pattern: String, finding: Finding): Boolean = when {
                pattern.startsWith("@") -> pattern.drop(1) == finding.rule.category.word
                pattern.endsWith("*") -> finding.type.startsWith(pattern.dropLast(1))
                '.' in pattern -> finding.memberName != null && pattern == "${finding.type}.${finding.memberName}"
                else -> pattern == finding.type
            }

            /** The category a pattern grants, or null when it names nothing the check refuses (a typo, a stale line). */
            fun categoryOf(pattern: String): Category? = when {
                pattern == "@network" -> Category.NETWORK
                pattern == "@shell" -> Category.SHELL
                pattern.endsWith("*") -> typeRuleFor(pattern.dropLast(1))?.category
                '.' in pattern -> memberRuleFor(pattern.substringBefore('.'), pattern.substringAfter('.'))?.category
                else -> typeRuleFor(pattern)?.category
            }

            fun parse(text: String, source: String = ALLOW_FILE): Allowlist {
                val grants = text.lines().mapIndexedNotNull { i, raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) return@mapIndexedNotNull null
                    val parts = line.split(Regex("\\s+"))
                    fun fail(why: String): Nothing = throw GradleException("$source:${i + 1}: $why: '$line'")
                    if (parts.size != 2) fail("expected '<module dir> <pattern>'")
                    val (module, pattern) = parts
                    if (!patternShape.matches(pattern)) fail("not a pattern")
                    val category = categoryOf(pattern) ?: fail("the pattern names nothing the egress check refuses")
                    // A tunnel module (or the app) may hold network grants only where it may hold INTERNET; core modules
                    // are libraries of those four and are granted type by type, never a whole category.
                    if (category == Category.NETWORK && !module.startsWith("core/") && module !in Permissions.internetAllowedIn) {
                        fail("$module may not be granted network APIs; only ${Permissions.internetAllowedIn.sorted()} and core modules may")
                    }
                    if ((module.startsWith("core/") || module == "app") && pattern.startsWith("@")) {
                        fail("$module is granted type by type, not $pattern (category grants are for tunnel modules only)")
                    }
                    Grant(module, pattern, i + 1, category)
                }
                return Allowlist(grants)
            }

            fun read(file: File): Allowlist {
                if (!file.isFile) throw GradleException("$ALLOW_FILE is missing: the egress check fails closed without it.")
                return parse(file.readText())
            }
        }
    }

    /** Grants of [module] that match none of its [findings]: stale lines, or a typo for another module. */
    fun unusedGrants(module: String, findings: List<Finding>, allowlist: Allowlist): List<Allowlist.Grant> =
        allowlist.grantsFor(module).filter { g -> findings.none { Allowlist.matches(g.pattern, it) } }

    /** Grants naming a module that is not in the build. */
    fun unknownModuleGrants(allowlist: Allowlist, modules: Set<String>): List<Allowlist.Grant> =
        allowlist.grants.filter { it.module !in modules }

    /** The findings [allowlist] does not grant to [module]. */
    fun refused(module: String, findings: List<Finding>, allowlist: Allowlist): List<Finding> =
        findings.filterNot { allowlist.allows(module, it) }

    fun failureMessage(module: String, refused: List<Finding>): String = buildString {
        append("$module references network or shell APIs that $ALLOW_FILE does not grant it (hard rule 1):\n")
        refused.sortedWith(compareBy({ it.inClass }, { it.location }, { it.type })).forEach { append("  ").append(it.describe()).append('\n') }
        append("Remove the reference, or move the code into a module that is granted it. Granting a new module is a gate change: ")
        append("add a line to $ALLOW_FILE with a note, and the owner merges it.")
    }
}

/** Checks one module's compiled classes (main source set) against its grants in buildSrc/egress.allow. */
abstract class VerifyModuleEgressTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowFile: RegularFileProperty

    @get:Input
    abstract val modulePath: Property<String>

    /** The tasks whose outputs are [classDirs]; empty means a renamed compile task, and the check fails closed. */
    @get:Input
    abstract val classProducers: ListProperty<String>

    /** True when the module has main sources, so finding no class file means the check is looking in the wrong place. */
    @get:Input
    abstract val hasMainSources: Property<Boolean>

    /** Every module directory in the build, so a grant for a module that does not exist is refused. */
    @get:Input
    abstract val knownModules: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val module = modulePath.get()
        val allowlist = Egress.Allowlist.read(allowFile.get().asFile)
        Egress.unknownModuleGrants(allowlist, knownModules.get().toSet()).takeIf { it.isNotEmpty() }?.let { bad ->
            throw GradleException(bad.joinToString("\n") { "${Egress.ALLOW_FILE}:${it.line}: no module ${it.module} in this build" })
        }
        if (classProducers.get().isEmpty()) {
            throw GradleException("$module: the egress check found no compile task to read classes from (renamed by a plugin update, or a flavor?); it fails closed.")
        }
        val classFiles = classDirs.asFileTree.matching { include("**/*.class") }.files
            .filter { it.name != "module-info.class" && !it.invariantSeparatorsPath.contains("/META-INF/versions/") }
            .sortedBy { it.invariantSeparatorsPath }
        val findings = classFiles.flatMap { file ->
            try {
                Egress.scan(file.readBytes())
            } catch (e: Exception) {
                throw GradleException("$module: the egress check cannot read $file (${e.message}); it fails closed.", e)
            }
        }
        if (classFiles.isEmpty() && hasMainSources.get()) {
            throw GradleException("$module has main sources but ${classProducers.get()} produced no class file for the egress check; it fails closed.")
        }
        val refused = Egress.refused(module, findings, allowlist)
        if (refused.isNotEmpty()) throw GradleException(Egress.failureMessage(module, refused))

        Egress.unusedGrants(module, findings, allowlist).takeIf { it.isNotEmpty() }?.let { unused ->
            throw GradleException(
                unused.joinToString("\n", postfix = "\nRemove the stale line (a gate change) or fix the module name.") {
                    "${Egress.ALLOW_FILE}:${it.line}: $module '${it.pattern}' matches nothing in $name's classes"
                },
            )
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(
            buildString {
                append("$module: ${classFiles.size} classes, ${findings.size} granted references\n")
                val byGrant: Map<Egress.Allowlist.Grant, List<Egress.Finding>> = findings.groupBy { f -> allowlist.grantFor(module, f)!! }
                for (grant in byGrant.keys.sortedBy { g -> g.line }) {
                    append("${Egress.ALLOW_FILE}:${grant.line} ${grant.pattern}\n")
                    for (f in byGrant.getValue(grant)) append("  ").append(f.describe()).append('\n')
                }
            },
        )
        logger.info("Egress OK for $module (${classFiles.size} classes, ${findings.size} granted references)")
    }
}
