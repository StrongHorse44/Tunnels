package io.github.stronghorse44.tunnels.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.github.stronghorse44.tunnels.install.ApkFacts
import io.github.stronghorse44.tunnels.install.DeviceFacts
import io.github.stronghorse44.tunnels.install.InstalledFacts
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkInfo(
    val label: String,
    val icon: ImageBitmap?,
    val facts: ApkFacts,
    val installed: InstalledFacts?,
    val totalBytes: Long,
    val apkCount: Int,
)

object ApkInspector {
    fun device(context: Context) = DeviceFacts(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), context.packageName)

    fun inspect(context: Context, base: File, all: List<File>): ApkInfo {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong() or PackageManager.GET_PERMISSIONS.toLong()
        val info = pm.getPackageArchiveInfo(base.path, PackageManager.PackageInfoFlags.of(flags))
            ?: throw IOException("Android couldn't read this APK. It may be damaged or incomplete.")
        val app = info.applicationInfo ?: throw IOException("The APK has no application entry.")
        app.sourceDir = base.path
        app.publicSourceDir = base.path

        val label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName)
        val icon = runCatching { app.loadIcon(pm).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        val signers = info.signingInfo?.apkContentsSigners.toListOrEmpty().map(::sha256).toSet()
        val lineage = info.signingInfo?.signingCertificateHistory.toListOrEmpty().map(::sha256).toSet() + signers

        val facts = ApkFacts(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            nativeAbis = all.flatMap(::nativeAbis).toSet(),
            testOnly = app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            signerSha256 = signers,
            lineageSha256 = lineage,
            requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
        )
        return ApkInfo(label, icon, facts, installed(context, info.packageName), all.sumOf { it.length() }, all.size)
    }

    fun installed(context: Context, packageName: String): InstalledFacts? {
        val pm = context.packageManager
        val info: PackageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return InstalledFacts(
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            signerSha256 = info.signingInfo?.apkContentsSigners.toListOrEmpty().map(::sha256).toSet(),
            installerPackage = runCatching { pm.getInstallSourceInfo(packageName).installingPackageName }.getOrNull(),
            debuggable = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    private fun nativeAbis(apk: File): Set<String> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .mapNotNull { it.split('/').getOrNull(1) }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) }

    private fun <T> Array<T>?.toListOrEmpty(): List<T> = this?.toList() ?: emptyList()
}

/** "AB:CD:EF:…:12" for display. */
fun shortFingerprint(hex: String): String =
    hex.chunked(2).let { (it.take(4) + listOf("…") + it.takeLast(4)).joinToString(":") }
