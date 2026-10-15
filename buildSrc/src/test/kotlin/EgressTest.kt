import egressfixtures.CallsShell
import egressfixtures.ConnectsLocalSocket
import egressfixtures.ExecsThroughOs
import egressfixtures.JavaSocketRef
import egressfixtures.NamesAProperty
import egressfixtures.PingsThroughOs
import egressfixtures.ReadsOsErrors
import egressfixtures.StreamsMedia
import egressfixtures.CatchesSocketTimeout
import egressfixtures.ExecsRuntime
import egressfixtures.HoldsSocket
import egressfixtures.ListsSockets
import egressfixtures.OpensSocket
import egressfixtures.OpensUrl
import egressfixtures.ParsesUri
import egressfixtures.Plain
import egressfixtures.ReflectsSocket
import egressfixtures.ResolvesName
import egressfixtures.StartsProcess
import egressfixtures.SwitchesThenConnects
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class EgressTest {
    private fun bytesOf(type: Class<*>): ByteArray =
        type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }

    private fun scan(type: Class<*>) = Egress.scan(bytesOf(type))

    private fun types(type: Class<*>) = scan(type).map { it.type }.toSet()

    @Test
    fun aSocketIsCaughtWithItsMethodAndMember() {
        val findings = scan(OpensSocket::class.java)
        assertTrue(findings.toString(), findings.all { it.location == "method open()Ljava/lang/Object;" && it.type == "java/net/Socket" })
        assertTrue(findings.any { it.via == null }) // the `new`
        val f = findings.single { it.via != null }
        assertEquals("java/net/Socket", f.type)
        assertEquals("java/net/Socket.<init> (Ljava/lang/String;I)V", f.via)
        assertEquals(Egress.Category.NETWORK, f.rule.category)
        assertTrue(f.describe(), f.describe().startsWith("egressfixtures.OpensSocket, method open()Ljava/lang/Object;: java/net/Socket"))
    }

    @Test
    fun uriEncoderAndAddressValuesPass() {
        assertEquals(emptyList<Egress.Finding>(), scan(ParsesUri::class.java))
        assertEquals(emptyList<Egress.Finding>(), scan(Plain::class.java))
    }

    @Test
    fun aNameLookupIsCaughtByMember() {
        val f = scan(ResolvesName::class.java).single()
        assertEquals("java/net/InetAddress", f.type)
        assertEquals("getByName", f.memberName)
        assertTrue(f.rule is Egress.Rule.Member)
    }

    @Test
    fun aClassNameInAStringIsCaught() {
        val f = scan(ReflectsSocket::class.java).single()
        assertEquals("java/net/Socket", f.type)
        assertEquals("string constant \"java.net.Socket\"", f.via)
    }

    @Test
    fun descriptorsSignaturesCatchesAndReturnTypesAreCaught() {
        assertTrue(scan(HoldsSocket::class.java).any { it.location == "field socket" && it.type == "java/net/Socket" })
        assertTrue(scan(ListsSockets::class.java).any { it.location == "field sockets" && it.type == "java/net/Socket" })
        // URI is a value; its toURL() returns a URL, which is not.
        val url = scan(OpensUrl::class.java).single()
        assertEquals("java/net/URL", url.type)
        assertEquals("java/net/URI.toURL ()Ljava/net/URL;", url.via)
        assertTrue("java/net/SocketTimeoutException" in types(CatchesSocketTimeout::class.java))
    }

    @Test
    fun theWalkStaysInStepAcrossSwitches() {
        val findings = scan(SwitchesThenConnects::class.java)
        assertTrue(findings.toString(), findings.any { it.location == "method connect(I)Ljava/lang/Object;" && it.via == "java/net/Socket.<init> ()V" })
        assertFalse(findings.any { it.location == "constant pool" })
    }

    @Test
    fun deepModeShellAndChildProcessesAreCaught() {
        val shell = scan(CallsShell::class.java)
        assertTrue(shell.all { it.rule.category == Egress.Category.SHELL })
        assertTrue(shell.any { it.type == "io/github/stronghorse44/tunnels/deepmode/ShizukuShell" && it.location.startsWith("method go") })
        assertTrue("java/lang/ProcessBuilder" in types(StartsProcess::class.java))
        val exec = scan(ExecsRuntime::class.java)
        assertEquals(listOf("exec"), exec.map { it.memberName })
    }

    @Test
    fun rulesKnowValueTypesFromConnections() {
        listOf("java/net/URI", "java/net/URLEncoder", "java/net/URLDecoder", "java/net/IDN", "java/net/InetAddress", "android/net/Network", "android/net/Uri")
            .forEach { assertEquals(it, null, Egress.typeRuleFor(it)) }
        listOf(
            "java/net/Socket", "java/net/URL", "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/DatagramSocket",
            "java/net/ServerSocket", "java/net/Proxy", "java/net/NetworkInterface", "java/net/InetSocketAddress", "javax/net/ssl/HttpsURLConnection",
            "javax/net/SocketFactory", "java/nio/channels/SocketChannel", "java/nio/channels/DatagramChannel", "android/net/http/HttpEngine",
            "android/net/nsd/NsdManager", "android/app/DownloadManager", "android/app/DownloadManager\$Request", "android/webkit/WebView",
            "android/webkit/WebViewClient", "okhttp3/OkHttpClient", "io/ktor/client/HttpClient", "android/net/VpnService\$Builder",
            "io/github/stronghorse44/tunnels/deepmode/ShizukuShell\$Session", "io/github/stronghorse44/tunnels/deepmode/IDeepShell\$Stub",
            "rikka/shizuku/Shizuku",
        ).forEach { assertTrue(it, Egress.typeRuleFor(it) != null) }
        assertEquals(null, Egress.typeRuleFor("java/nio/channels/FileChannel"))
        assertEquals(null, Egress.typeRuleFor("io/github/stronghorse44/tunnels/deepmode/ShizukuStatus"))
        assertTrue(Egress.memberRuleFor("android/net/Network", "bindSocket") != null)
        assertEquals(null, Egress.memberRuleFor("android/net/Network", "getNetworkHandle"))
    }

    private val sample = """
        # comment
        tunnels/traffic   @network            # the session
        core/lan          java/net/Socket     # ProbeBinding
        core/dns          java/net/InetAddress.getByName
        tunnels/deepmode  @shell
        tunnels/emissions io/github/stronghorse44/tunnels/deepmode/ShizukuShell
        core/x            javax/net/*
    """.trimIndent()

    private fun finding(type: Class<*>) = scan(type).first()

    @Test
    fun theAllowlistGrantsByCategoryTypePrefixAndMember() {
        val allow = Egress.Allowlist.parse(sample)
        assertEquals(6, allow.grants.size)
        val socket = finding(OpensSocket::class.java)
        assertTrue(allow.allows("tunnels/traffic", socket))
        assertTrue(allow.allows("core/lan", socket))
        assertFalse(allow.allows("tunnels/convert", socket))
        assertFalse(allow.allows("tunnels/deepmode", socket))
        val lookup = finding(ResolvesName::class.java)
        assertTrue(allow.allows("core/dns", lookup))
        assertFalse(allow.allows("core/lan", lookup))
        val shell = scan(CallsShell::class.java)
        assertTrue(shell.all { allow.allows("tunnels/emissions", it) && allow.allows("tunnels/deepmode", it) })
        assertFalse(shell.any { allow.allows("tunnels/traffic", it) })
        assertEquals(listOf(socket), Egress.refused("tunnels/convert", listOf(socket), allow))
        val message = Egress.failureMessage("tunnels/convert", listOf(socket))
        assertTrue(message, message.contains("tunnels/convert") && message.contains("egressfixtures.OpensSocket, method open") && message.contains("java/net/Socket"))
    }

    private fun refusedLine(text: String, expect: String) {
        try {
            Egress.Allowlist.parse(text)
            fail("parsed: $text")
        } catch (e: GradleException) {
            assertTrue(e.message, e.message!!.contains(expect))
        }
    }

    @Test
    fun theAllowlistRefusesBadLines() {
        refusedLine("tunnels/convert @network", "may not be granted network APIs")
        refusedLine("app java/net/Socket", "may not be granted network APIs")
        refusedLine("core/lan", "expected")
        refusedLine("core/lan java/net/Socket extra", "expected")
        refusedLine("core/lan java/util/List", "names nothing")
        refusedLine("core/lan java/net/Socket.<init>", "names nothing")
        refusedLine("core/lan java.net.Socket", "not a pattern")
        // Shell grants are not tied to INTERNET.
        Egress.Allowlist.parse("tunnels/emissions @shell")
    }

    /** The checked-in list (the test runs in buildSrc/). */
    private val realAllowlist by lazy { Egress.Allowlist.read(File("egress.allow")) }

    @Test
    fun theCheckedInAllowlistParsesAndGrantsOnlyItsModules() {
        val modules = realAllowlist.grants.map { it.module }.toSet()
        val network = realAllowlist.grants.filter { it.category == Egress.Category.NETWORK }.map { it.module }.toSet()
        val shell = realAllowlist.grants.filter { it.category == Egress.Category.SHELL }.map { it.module }.toSet()
        assertEquals(setOf("tunnels/deepmode", "tunnels/emissions"), shell)
        assertTrue(network.toString(), network.all { it.startsWith("core/") || it in Permissions.internetAllowedIn })
        assertFalse("tunnels/convert" in modules)
        assertFalse("app" in modules)
        val socket = finding(OpensSocket::class.java)
        assertFalse(realAllowlist.allows("tunnels/convert", socket))
        assertFalse(realAllowlist.allows("tunnels/emissions", socket))
    }

    @Test
    fun withoutItsShizukuShellLineEmissionsWouldFail() {
        val shell = scan(CallsShell::class.java)
        assertTrue(shell.isNotEmpty())
        assertTrue(Egress.refused("tunnels/emissions", shell, realAllowlist).isEmpty())
        val text = File("egress.allow").readLines().filterNot { it.startsWith("tunnels/emissions") && "ShizukuShell" in it }.joinToString("\n")
        val mutant = Egress.Allowlist.parse(text)
        val refused = Egress.refused("tunnels/emissions", shell, mutant)
        assertEquals(shell, refused)
        assertTrue(Egress.failureMessage("tunnels/emissions", refused).contains("io/github/stronghorse44/tunnels/deepmode/ShizukuShell"))
    }

    @Test
    fun theReaderHandlesEveryClassOfTheKotlinStandardLibraryAndTheGradleApi() {
        var classes = 0
        for (anchor in listOf(KotlinVersion::class.java, GradleException::class.java)) {
            val jar = File(anchor.protectionDomain.codeSource.location.toURI())
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.endsWith("module-info.class") && "/META-INF/versions/" !in it.name }.forEach { entry ->
                    val parsed = ClassFileReader.parse(zip.getInputStream(entry).use { it.readBytes() })
                    assertEquals(entry.name.removeSuffix(".class"), parsed.name)
                    classes++
                }
            }
        }
        assertTrue("$classes", classes > 5000)
    }

    @Test
    fun descriptorTypes() {
        assertEquals(listOf("java/net/Socket", "java/lang/String"), ClassFileReader.typesIn("(Ljava/net/Socket;[Ljava/lang/String;I)V"))
        assertEquals(listOf("java/util/List", "java/net/Socket"), ClassFileReader.typesIn("Ljava/util/List<Ljava/net/Socket;>;"))
        assertEquals("java/net/Socket", ClassFileReader.classNameOf("[[Ljava/net/Socket;"))
        assertEquals(null, ClassFileReader.classNameOf("[I"))
    }
    @Test
    fun aPingThroughOsIsCaught() {
        // LookCloserProbe's shape: no java.net type but InetAddress, which is a value.
        val findings = scan(PingsThroughOs::class.java)
        assertEquals(setOf("socket", "sendto"), findings.map { it.memberName }.toSet())
        assertTrue(findings.all { it.type == "android/system/Os" && it.rule.category == Egress.Category.NETWORK })
        assertTrue(findings.all { it.location.startsWith("method ping") })
        assertEquals(emptyList<Egress.Finding>(), scan(ReadsOsErrors::class.java))
        val exec = scan(ExecsThroughOs::class.java).single()
        assertEquals("execv", exec.memberName)
        assertEquals(Egress.Category.SHELL, exec.rule.category)
    }

    @Test
    fun localSocketsAndMediaFromAUrlAreCaught() {
        val local = scan(ConnectsLocalSocket::class.java)
        assertEquals(setOf("android/net/LocalSocket", "android/net/LocalSocketAddress"), local.map { it.type }.toSet())
        val media = scan(StreamsMedia::class.java).single()
        assertEquals("android/media/MediaPlayer", media.type)
        assertEquals("setDataSource", media.memberName)
        assertTrue(media.location.startsWith("method go"))
        assertTrue(Egress.typeRuleFor("android/net/rtp/AudioStream") != null)
        assertTrue(Egress.memberRuleFor("android/widget/VideoView", "setVideoURI") != null)
    }

    @Test
    fun methodReferencesNamedOnlyByABootstrapAreCaughtAtTheirMethod() {
        val java = scan(JavaSocketRef::class.java)
        assertTrue(java.toString(), java.any { it.type == "java/net/Socket" && it.location == "method sockets()Ljava/util/function/Supplier;" })
        // Kotlin compiles this one to a small class of its own; the lookup sits in its apply().
        val kotlin = scan(Class.forName("egressfixtures.LooksUpByReference\$resolver\$1"))
        assertTrue(kotlin.toString(), kotlin.any { it.type == "java/net/InetAddress" && it.memberName == "getByName" })
    }

    /** A class file whose only mention of java/net/Socket is an unused CONSTANT_Class: only the pool sweep sees it. */
    private fun poolOnlyClass(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt()); writeShort(0); writeShort(52)
            writeShort(7)
            writeByte(1); writeUTF("egressfixtures/PoolOnly")
            writeByte(7); writeShort(1)
            writeByte(1); writeUTF("java/lang/Object")
            writeByte(7); writeShort(3)
            writeByte(1); writeUTF("java/net/Socket")
            writeByte(7); writeShort(5)
            writeShort(0x21); writeShort(2); writeShort(4)
            writeShort(0); writeShort(0); writeShort(0); writeShort(0)
        }
        return out.toByteArray()
    }

    @Test
    fun theConstantPoolSweepCatchesWhatNoInstructionNames() {
        val f = Egress.scan(poolOnlyClass()).single()
        assertEquals("egressfixtures/PoolOnly", f.inClass)
        assertEquals("constant pool", f.location)
        assertEquals("java/net/Socket", f.type)
    }

    @Test
    fun aPropertyNameIsNotAClassName() {
        assertEquals(emptyList<Egress.Finding>(), scan(NamesAProperty::class.java))
    }

    @Test
    fun staleAndUnknownGrantsAreFound() {
        val allow = Egress.Allowlist.parse("core/lan java/net/Socket\ncore/lan java/net/DatagramSocket\ncore/nope java/net/Socket")
        val socket = finding(OpensSocket::class.java)
        assertEquals(listOf("java/net/DatagramSocket"), Egress.unusedGrants("core/lan", listOf(socket), allow).map { it.pattern })
        assertEquals(listOf("core/nope"), Egress.unknownModuleGrants(allow, setOf("core/lan", "tunnels/convert")).map { it.module })
        refusedLine("core/lan @network", "type by type")
        refusedLine("core/emissions @shell", "type by type")
    }
}
