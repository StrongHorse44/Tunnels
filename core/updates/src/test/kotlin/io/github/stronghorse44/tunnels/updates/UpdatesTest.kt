package io.github.stronghorse44.tunnels.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    /** Trimmed from what api.github.com returns for this repository's releases, newest first. */
    private val sample = """
        [
          {
            "url": "https://api.github.com/repos/StrongHorse44/Tunnels/releases/3",
            "tag_name": "debug-31", "name": "Debug build #31 (a8b15e7)", "draft": false, "prerelease": true,
            "published_at": "2026-10-02T09:00:00Z",
            "body": "Metro map: labels placed clear of each other\n\n---\nDebug build #31",
            "assets": [
              {"id": 3101, "name": "tunnels-debug-31.apk", "size": 18234567, "content_type": "application/vnd.android.package-archive"},
              {"id": 3102, "name": "tunnels-debug-31.apk.sha256", "size": 86}
            ]
          },
          {
            "tag_name": "v0.2.0", "name": "Tunnels 0.2.0", "draft": false, "prerelease": false, "published_at": null, "body": null,
            "assets": [
              {"id": 2001, "name": "tunnels-0.2.0.apk", "size": 17000000},
              {"id": 2002, "name": "tunnels-0.2.0.apk.sha256", "size": 84}
            ]
          },
          {
            "tag_name": "debug-29", "name": "Debug build #29", "draft": false, "prerelease": true, "body": "",
            "assets": [{"id": 2901, "name": "tunnels-debug-29.apk", "size": 18000000}]
          },
          {"tag_name": "v0.3.0", "name": "Draft", "draft": true, "prerelease": false, "assets": [{"id": 9, "name": "tunnels-0.3.0.apk", "size": 1}]},
          {"tag_name": "debug-latest", "name": "old pointer", "draft": false, "prerelease": true, "assets": []},
          {"tag_name": "v0.1.0", "name": "Tunnels 0.1.0", "draft": false, "prerelease": false, "body": "\u00e9t\u00e9 \ud83d\ude80", "assets": [{"id": 1001, "name": "tunnels-0.1.0.apk", "size": 1}]},
          "not a release"
        ]
    """.trimIndent()

    @Test
    fun jsonReaderHandlesWhatGitHubSends() {
        @Suppress("UNCHECKED_CAST")
        val obj = MiniJson.parse("""{"a": [1, -2.5, 3e2, true, false, null, "x\"y\\z\/\n\u0041"], "b": {}, "c": []}""") as Map<String, Any?>
        assertEquals(listOf(1L, -2.5, 300.0, true, false, null, "x\"y\\z/\nA"), obj["a"])
        assertEquals(emptyMap<String, Any?>(), obj["b"])
        assertEquals(emptyList<Any?>(), obj["c"])
        assertEquals(12345678901L, MiniJson.parse(" 12345678901 "))
        // A surrogate pair stays one emoji.
        assertEquals("\uD83D\uDE80", MiniJson.parse("\"\\ud83d\\ude80\""))
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "[1] x", "01x", "{\"a\":}", "\"\u0001\"", "-", "1.", "[\"\\q\"]")) {
            assertTrue("rejects '$bad'", runCatching { MiniJson.parse(bad) }.isFailure)
        }
        // Nesting is capped, so a hostile document cannot overflow the stack.
        val deep = "[".repeat(MiniJson.MAX_DEPTH + 2) + "]".repeat(MiniJson.MAX_DEPTH + 2)
        assertTrue(runCatching { MiniJson.parse(deep) }.exceptionOrNull() is MiniJson.JsonException)
        val ok = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH)
        assertTrue(runCatching { MiniJson.parse(ok) }.isSuccess)
    }

    @Test
    fun releasesParse() {
        val releases = Updates.parseReleases(sample)
        assertEquals(listOf("debug-31", "v0.2.0", "debug-29", "v0.3.0", "debug-latest", "v0.1.0"), releases.map { it.tag })
        val d31 = releases.first()
        assertTrue(d31.prerelease)
        assertFalse(d31.draft)
        assertEquals("Debug build #31 (a8b15e7)", d31.name)
        assertEquals(listOf(ReleaseAsset(3101, "tunnels-debug-31.apk", 18234567), ReleaseAsset(3102, "tunnels-debug-31.apk.sha256", 86)), d31.assets)
        assertEquals("", releases[1].body)
        assertNull(releases[1].publishedAt)
        assertEquals("été \uD83D\uDE80", releases.last().body)
        assertTrue(runCatching { Updates.parseReleases("{\"message\": \"Not Found\"}") }.isFailure)
    }

    @Test
    fun debugBuildsFollowDebugReleases() {
        val releases = Updates.parseReleases(sample)
        assertEquals(Channel.DEBUG, Updates.channelOf("io.github.stronghorse44.tunnels.debug"))
        assertEquals(Channel.RELEASE, Updates.channelOf("io.github.stronghorse44.tunnels"))
        val update = Updates.newest(releases, Channel.DEBUG, installedVersionCode = 27)!!
        assertEquals(31L, update.versionCode)
        assertEquals("Debug build #31", update.label)
        assertEquals(3101L, update.apk.id)
        assertEquals(3102L, update.checksum?.id)
        // Already on 31: nothing newer, and "latest" still names it.
        assertNull(Updates.newest(releases, Channel.DEBUG, installedVersionCode = 31))
        assertEquals(31L, Updates.latest(releases, Channel.DEBUG)?.versionCode)
        // Build 29 has no checksum file.
        assertNull(Updates.newest(releases, Channel.DEBUG, 28)!!.let { if (it.versionCode == 29L) it.checksum else null })
        assertEquals(29L, Updates.newest(releases.filter { it.tag != "debug-31" }, Channel.DEBUG, 28)?.versionCode)
    }

    @Test
    fun releaseBuildsFollowVersionTagsOnly() {
        val releases = Updates.parseReleases(sample)
        val update = Updates.newest(releases, Channel.RELEASE, installedVersionCode = 1_000)!!
        assertEquals("Tunnels 0.2.0", update.label)
        assertEquals(2_000L, update.versionCode)
        assertEquals("tunnels-0.2.0.apk", update.apk.name)
        assertEquals(2002L, update.checksum?.id)
        // The draft v0.3.0 and every debug prerelease are ignored.
        assertNull(Updates.newest(releases, Channel.RELEASE, installedVersionCode = 2_000))
        // Version codes as release.yml derives them from the tag.
        val big = Release("v1.12.3", "", false, false, "", null, listOf(ReleaseAsset(1, "tunnels-1.12.3.apk", 1)))
        assertEquals(1_012_003L, Updates.candidate(big, Channel.RELEASE)?.versionCode)
        assertNull(Updates.candidate(big.copy(prerelease = true), Channel.RELEASE))
        assertNull(Updates.candidate(big.copy(assets = listOf(ReleaseAsset(1, "other.apk", 1))), Channel.RELEASE))
        assertNull(Updates.candidate(big, Channel.DEBUG))
    }

    @Test
    fun channelsNeverFollowEachOther() {
        // A release install (package without .debug) and a debug install (with it) each see only their own tags,
        // whatever the other channel's version codes are: debug build codes are CI run numbers (tens to
        // hundreds) and release codes start at 1 000, so a code comparison alone would mix them up.
        fun release(tag: String, name: String, assets: List<String>, prerelease: Boolean = false) =
            Release(tag, name, prerelease, false, "", null, assets.mapIndexed { i, a -> ReleaseAsset(i + 1L, a, 1) })
        val releases = listOf(
            release("debug-93", "Debug build #93 (abc1234)", listOf("tunnels-debug-93.apk", "tunnels-debug-93.apk.sha256"), prerelease = true),
            release("v0.1.0", "v0.1.0", listOf("tunnels-0.1.0.apk", "tunnels-0.1.0.apk.sha256")),
        )
        // The installed release 0.0.1 (code 1) is offered v0.1.0 (code 1 000), never debug-93.
        val forRelease = Updates.newest(releases, Updates.channelOf("io.github.stronghorse44.tunnels"), installedVersionCode = 1)!!
        assertEquals("v0.1.0", forRelease.release.tag)
        assertEquals(1_000L, forRelease.versionCode)
        // A debug install on build 50 is offered debug-93, never v0.1.0 (whose code, 1 000, is higher).
        val forDebug = Updates.newest(releases, Updates.channelOf("io.github.stronghorse44.tunnels.debug"), installedVersionCode = 50)!!
        assertEquals("debug-93", forDebug.release.tag)
        assertNull(Updates.newest(releases, Channel.DEBUG, installedVersionCode = 93))
        assertNull(Updates.newest(releases, Channel.RELEASE, installedVersionCode = 1_000))
        // The tag decides, not the title: a debug-looking title on a version tag is a release, and a version-looking
        // title on a debug tag is a debug build.
        val odd = listOf(
            release("v0.2.0", "Debug build #99", listOf("tunnels-0.2.0.apk")),
            release("debug-120", "v9.9.9", listOf("tunnels-debug-120.apk"), prerelease = true),
        )
        assertEquals("v0.2.0", Updates.newest(odd, Channel.RELEASE, 1)?.release?.tag)
        assertNull(Updates.newest(odd, Channel.RELEASE, 2_000))
        assertEquals("debug-120", Updates.newest(odd, Channel.DEBUG, 1)?.release?.tag)
        // A prerelease or a differently named APK on a version tag is not offered to release installs.
        assertNull(Updates.candidate(release("v0.3.0", "v0.3.0", listOf("tunnels-0.3.0.apk"), prerelease = true), Channel.RELEASE))
        assertNull(Updates.candidate(release("v0.3.0", "v0.3.0", listOf("tunnels-debug-0.3.0.apk")), Channel.RELEASE))
        // Tags release.yml refuses are not versions here either (leading zeros, four digits, extra parts, no v).
        for (tag in listOf("v01.2.3", "v1.02.3", "v1.2.03", "v1000.0.0", "v1.2", "v1.2.3.4", "1.2.3", "v1.2.3-rc1")) {
            val apk = "tunnels-${tag.removePrefix("v")}.apk"
            assertNull("$tag is not a release version", Updates.candidate(release(tag, tag, listOf(apk)), Channel.RELEASE))
        }
    }

    @Test
    fun versionCodesOrderLikeVersionsAndFitAnInt() {
        // release.yml: code = MAJOR * 1 000 000 + MINOR * 1 000 + PATCH, each part 0-999.
        fun code(tag: String) = Updates.candidate(
            Release(tag, tag, false, false, "", null, listOf(ReleaseAsset(1, "tunnels-${tag.removePrefix("v")}.apk", 1))),
            Channel.RELEASE,
        )?.versionCode
        assertEquals(1_000L, code("v0.1.0"))
        assertEquals(1L, code("v0.0.1"))
        assertEquals(999_999_999L, code("v999.999.999"))
        assertTrue(999_999_999L < Int.MAX_VALUE)
        val ordered = listOf("v0.0.1", "v0.0.2", "v0.0.999", "v0.1.0", "v0.1.1", "v0.999.999", "v1.0.0", "v1.2.3", "v1.10.0", "v2.0.0")
        assertEquals(ordered.map { code(it) }, ordered.map { code(it) }.sortedBy { it })
        assertEquals(ordered.size, ordered.map { code(it) }.toSet().size)
    }

    @Test
    fun checksumFilesInEveryShapeSha256sumWrites() {
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(hex, Updates.checksumFor("$hex  tunnels-0.2.0.apk\n", "tunnels-0.2.0.apk"))
        assertEquals(hex, Updates.checksumFor("${hex.uppercase()} *tunnels-0.2.0.apk", "tunnels-0.2.0.apk"))
        assertEquals(hex, Updates.checksumFor("$hex  ./out/tunnels-0.2.0.apk", "tunnels-0.2.0.apk"))
        assertEquals(hex, Updates.checksumFor(hex, "tunnels-0.2.0.apk"))
        assertNull(Updates.checksumFor("$hex  other.apk", "tunnels-0.2.0.apk"))
        assertNull(Updates.checksumFor("not a checksum", "tunnels-0.2.0.apk"))
        assertNull(Updates.checksumFor("", "x"))
    }

    @Test
    fun onlyGitHubOverHttpsAndTheTokenOnlyToTheApi() {
        assertTrue(UpdateSource.isAllowed("https", "api.github.com"))
        assertTrue(UpdateSource.isAllowed("https", "objects.githubusercontent.com"))
        assertTrue(UpdateSource.isAllowed("https", "release-assets.githubusercontent.com"))
        assertTrue(UpdateSource.isAllowed("HTTPS", "GitHub.com"))
        assertFalse(UpdateSource.isAllowed("http", "api.github.com"))
        assertFalse(UpdateSource.isAllowed("https", "github.com.evil.example"))
        assertFalse(UpdateSource.isAllowed("https", "githubusercontent.com.evil.example"))
        assertFalse(UpdateSource.isAllowed("https", "example.com"))
        assertTrue(UpdateSource.sendsToken("api.github.com"))
        assertFalse(UpdateSource.sendsToken("objects.githubusercontent.com"))
        assertFalse(UpdateSource.sendsToken("github.com"))
        assertEquals("https://api.github.com/repos/StrongHorse44/Tunnels/releases/assets/3101", UpdateSource.assetUrl(3101))
    }

    @Test
    fun theDownloadMustBeThisAppNewerAndSameSigner() {
        val pkg = "io.github.stronghorse44.tunnels.debug"
        val key = setOf("aa".repeat(32))
        fun verify(
            apkPackage: String? = pkg,
            version: Long = 31,
            signers: Set<String> = key,
            history: List<String> = emptyList(),
            expected: String? = null,
            actual: String? = null,
        ) = UpdateCheck.verify(pkg, 27, key, apkPackage, version, signers, history, expected, actual)

        assertEquals(UpdateCheck.Verdict.Ok, verify())
        assertEquals(UpdateCheck.Verdict.Ok, verify(expected = "AB".repeat(32), actual = "ab".repeat(32)))
        // A rotated key whose lineage includes the installed one is the same publisher.
        assertEquals(UpdateCheck.Verdict.Ok, verify(signers = setOf("bb".repeat(32)), history = listOf("aa".repeat(32), "bb".repeat(32))))
        fun refused(v: UpdateCheck.Verdict, words: String) = assertTrue("$v", v is UpdateCheck.Verdict.Refused && v.reason.contains(words))
        refused(verify(apkPackage = null), "not an Android app")
        refused(verify(apkPackage = "com.example.other"), "not this app")
        refused(verify(version = 27), "not newer")
        refused(verify(signers = setOf("cc".repeat(32))), "different key")
        refused(verify(signers = emptySet()), "could not be read")
        refused(verify(expected = "ab".repeat(32), actual = "cd".repeat(32)), "checksum")
        refused(verify(expected = "ab".repeat(32), actual = null), "checksum")
    }

    @Test
    fun errorsSayWhatToDo() {
        assertTrue(UpdateErrors.forHttp(404, hasToken = false).contains("moved or renamed"))
        assertTrue(UpdateErrors.forHttp(404, hasToken = true).contains("cannot see StrongHorse44/Tunnels"))
        assertTrue(UpdateErrors.forHttp(401, hasToken = true).contains("paste a new one"))
        assertTrue(UpdateErrors.forHttp(403, hasToken = false).contains("Add a token"))
        assertTrue(UpdateErrors.forHttp(403, hasToken = true).contains("read-only access"))
        assertTrue(UpdateErrors.forHttp(502, hasToken = true).contains("Try again"))
        assertEquals("GitHub answered 418.", UpdateErrors.forHttp(418, hasToken = true))
        assertTrue(UpdateErrors.forNoConnection(networkPermission = false).contains("Network permission is off"))
        assertTrue(UpdateErrors.forNoConnection(networkPermission = true).contains("Could not reach GitHub"))
    }

    @Test
    fun tokensAreCleanedNotJudged() {
        val pat = "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab"
        assertEquals(pat, TokenFormat.clean("  $pat\n"))
        assertEquals("ghp_" + "a".repeat(36), TokenFormat.clean("ghp_" + "a".repeat(36)))
        assertNull(TokenFormat.clean("short"))
        assertNull(TokenFormat.clean("has a space in the middle of it ok"))
        assertNull(TokenFormat.clean("x".repeat(300)))
        assertNull(TokenFormat.clean("ghp_" + "é".repeat(30)))
        assertEquals("github_pat_…89ab", TokenFormat.hint(pat))
    }
}
