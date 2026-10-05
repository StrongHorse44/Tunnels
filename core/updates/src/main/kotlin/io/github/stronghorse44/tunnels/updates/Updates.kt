package io.github.stronghorse44.tunnels.updates

/**
 * Where Tunnels' own builds come from: the project's GitHub releases. The updater talks to [API_HOST] and,
 * for the file itself, to the hosts GitHub redirects downloads to; never anywhere else.
 */
object UpdateSource {
    const val OWNER = "StrongHorse44"
    const val REPO = "Tunnels"
    const val API_HOST = "api.github.com"
    /** The newest releases first; debug builds keep the last 10, so 30 always reaches the newest of both kinds. */
    const val RELEASES_URL = "https://$API_HOST/repos/$OWNER/$REPO/releases?per_page=30"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** The asset's file through the API: works for private repositories with a token and for public ones without. */
    fun assetUrl(id: Long): String = "https://$API_HOST/repos/$OWNER/$REPO/releases/assets/$id"

    /** Hosts a request may go to, including GitHub's download redirects. HTTPS only, checked on every hop. */
    fun isAllowed(scheme: String, host: String): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        val h = host.lowercase()
        return h == API_HOST || h == "github.com" || h == "objects.githubusercontent.com" ||
            h == "release-assets.githubusercontent.com" || h.endsWith(".githubusercontent.com")
    }

    /** The token goes to the API host alone, never to a redirect target. */
    fun sendsToken(host: String): Boolean = host.equals(API_HOST, ignoreCase = true)
}

data class ReleaseAsset(val id: Long, val name: String, val size: Long)

data class Release(
    val tag: String,
    val name: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val body: String,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>,
)

/** Which builds an installed Tunnels follows: debug builds follow "Debug build #N", release builds the vX.Y.Z tags. */
enum class Channel { DEBUG, RELEASE }

/** A newer build: its version, the APK to fetch and, when the release has one, the checksum file. */
data class Update(
    val channel: Channel,
    val versionCode: Long,
    val label: String,
    val release: Release,
    val apk: ReleaseAsset,
    val checksum: ReleaseAsset?,
)

object Updates {
    private val DEBUG_TAG = Regex("""debug-(\d{1,9})""")
    /** Exactly what release.yml accepts: each part 0-999 with no leading zero, so a tag has one version code. */
    private val RELEASE_TAG = Regex("""v(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""")

    /** Debug builds carry the `.debug` application id suffix. */
    fun channelOf(packageName: String): Channel = if (packageName.endsWith(".debug")) Channel.DEBUG else Channel.RELEASE

    /** GitHub's release list, newest first as GitHub returns it. Entries that do not parse are skipped. */
    fun parseReleases(json: String): List<Release> {
        val root = MiniJson.parse(json) as? List<*> ?: throw MiniJson.JsonException("a list of releases expected")
        return root.mapNotNull { item ->
            val r = item as? Map<*, *> ?: return@mapNotNull null
            val tag = r["tag_name"] as? String ?: return@mapNotNull null
            Release(
                tag = tag,
                name = r["name"] as? String ?: tag,
                prerelease = r["prerelease"] == true,
                draft = r["draft"] == true,
                body = r["body"] as? String ?: "",
                publishedAt = r["published_at"] as? String,
                assets = (r["assets"] as? List<*>).orEmpty().mapNotNull { a ->
                    val m = a as? Map<*, *> ?: return@mapNotNull null
                    val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
                    val name = m["name"] as? String ?: return@mapNotNull null
                    ReleaseAsset(id, name, (m["size"] as? Number)?.toLong() ?: -1L)
                },
            )
        }
    }

    /**
     * The release [r] as a build of [channel], or null when it is not one: debug builds are prereleases tagged
     * `debug-N` (N, the CI run number, is their version code) with `tunnels-debug-N.apk`; release builds are
     * releases tagged `vX.Y.Z` (version code X·1 000 000 + Y·1 000 + Z, as release.yml derives it; the channel comes
     * from the tag alone, never from the release's title) with
     * `tunnels-X.Y.Z.apk`. A `<apk>.sha256` file next to the APK is its checksum.
     */
    fun candidate(r: Release, channel: Channel): Update? {
        if (r.draft) return null
        return when (channel) {
            Channel.DEBUG -> {
                val n = DEBUG_TAG.matchEntire(r.tag)?.groupValues?.get(1)?.toLong() ?: return null
                val apk = r.assets.firstOrNull { it.name == "tunnels-debug-$n.apk" } ?: return null
                Update(channel, n, "Debug build #$n", r, apk, r.assets.firstOrNull { it.name == apk.name + ".sha256" })
            }
            Channel.RELEASE -> {
                if (r.prerelease) return null
                val m = RELEASE_TAG.matchEntire(r.tag) ?: return null
                val (x, y, z) = m.destructured
                val version = "$x.$y.$z"
                val apk = r.assets.firstOrNull { it.name == "tunnels-$version.apk" } ?: return null
                Update(channel, x.toLong() * 1_000_000 + y.toLong() * 1_000 + z.toLong(), "Tunnels $version", r, apk, r.assets.firstOrNull { it.name == apk.name + ".sha256" })
            }
        }
    }

    /** The newest build of [channel] newer than [installedVersionCode], or null when the installed one is the newest. */
    fun newest(releases: List<Release>, channel: Channel, installedVersionCode: Long): Update? =
        releases.mapNotNull { candidate(it, channel) }.filter { it.versionCode > installedVersionCode }.maxByOrNull { it.versionCode }

    /** The newest build of [channel] at all, newer or not: what "up to date" names. */
    fun latest(releases: List<Release>, channel: Channel): Update? = releases.mapNotNull { candidate(it, channel) }.maxByOrNull { it.versionCode }

    /**
     * The SHA-256 a `sha256sum` file gives for [fileName] ("<64 hex>  <name>" or "<64 hex> *<name>" per line), or
     * the only digest of a one-line file that names no file. Lower case; null when there is none.
     */
    fun checksumFor(text: String, fileName: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = lines.mapNotNull { line ->
            val m = Regex("""([0-9a-fA-F]{64})(?:\s+\*?(.+))?""").matchEntire(line) ?: return@mapNotNull null
            m.groupValues[1].lowercase() to m.groupValues[2].trim().substringAfterLast('/')
        }
        parsed.firstOrNull { it.second == fileName }?.let { return it.first }
        return parsed.singleOrNull()?.takeIf { it.second.isEmpty() }?.first
    }
}

/** What the downloaded APK must be before Tunnels hands it to the installer. */
object UpdateCheck {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /**
     * [installedSigners] are the SHA-256 digests of the installed app's current signing certificates;
     * [apkSigners] the downloaded APK's current ones and [apkHistory] its signing lineage (empty when it has none).
     * The APK must be this app, newer, and signed by the same key (or by a key that rotated from it).
     */
    fun verify(
        packageName: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        apkPackage: String?,
        apkVersionCode: Long,
        apkSigners: Set<String>,
        apkHistory: List<String> = emptyList(),
        expectedSha256: String? = null,
        actualSha256: String? = null,
    ): Verdict = when {
        apkPackage == null -> Verdict.Refused("The download is not an Android app. It may have been cut off; try again.")
        apkPackage != packageName -> Verdict.Refused("The download is $apkPackage, not this app ($packageName). Nothing was installed.")
        apkVersionCode <= installedVersionCode ->
            Verdict.Refused("The download is version $apkVersionCode, not newer than the installed $installedVersionCode.")
        expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true) ->
            Verdict.Refused("The download does not match the checksum published with the release. Nothing was installed; try again.")
        installedSigners.isEmpty() || apkSigners.isEmpty() -> Verdict.Refused("The signing certificates could not be read. Nothing was installed.")
        apkSigners == installedSigners || installedSigners.all { it in apkHistory } -> Verdict.Ok
        else -> Verdict.Refused(
            "The download is signed with a different key than the installed Tunnels. Android would refuse it, and it may not " +
                "come from your builds. Nothing was installed.",
        )
    }
}

/** What went wrong, in words, for the update screen. */
object UpdateErrors {
    /** An HTTP answer other than 200 from GitHub. [hasToken] says whether a token went with the request. */
    fun forHttp(code: Int, hasToken: Boolean): String = when {
        code == 401 -> "GitHub refused the token (401). It may have expired or been revoked: paste a new one."
        code == 403 && !hasToken ->
            "GitHub turned the request away (403), usually its limit for requests without a token. Add a token, or try again in an hour."
        code == 403 -> "GitHub refused access (403). The token needs read-only access to the contents of ${UpdateSource.OWNER}/${UpdateSource.REPO}."
        code == 404 && !hasToken ->
            "GitHub answered Not Found (404). The repository may have moved; if this is a private fork, Tunnels needs a read-only token to see its releases."
        code == 404 -> "GitHub answered Not Found (404): the token cannot see ${UpdateSource.OWNER}/${UpdateSource.REPO}. Give it access to that repository."
        code in 500..599 -> "GitHub had a problem ($code). Try again in a minute."
        else -> "GitHub answered $code."
    }

    /** No connection at all: on GrapheneOS most often the Network permission, otherwise no network. */
    fun forNoConnection(networkPermission: Boolean): String =
        if (!networkPermission) "Tunnels' Network permission is off, so it cannot reach GitHub. Turn it on for the check and off again afterwards."
        else "Could not reach GitHub. Check the connection (and any VPN or firewall in the way) and try again."
}

/** A GitHub token as pasted: trimmed, one word, a plausible length. GitHub decides whether it works. */
object TokenFormat {
    fun clean(input: String): String? {
        val t = input.trim()
        if (t.length !in 20..255 || t.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e }) return null
        return t
    }

    /** "github_pat_…x9Qa": enough to recognise a saved token, never enough to use it. */
    fun hint(token: String): String = token.take(11) + "…" + token.takeLast(4)
}
