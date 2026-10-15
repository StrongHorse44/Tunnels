package io.github.stronghorse44.tunnels.decoder

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sealed decoder on the emulator (spec section 7.2). "Via the service" means through DecoderClient; "in-process"
 * means this test's own ImageDecoder or PdfRenderer (test code may decode). Every test uses its own failure policy, so
 * counted failures in one test never refuse a file in another.
 */
@RunWith(AndroidJUnit4::class)
class DecoderClientTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir: File = context.cacheDir.resolve("decoder-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun policy() = FailurePolicy { SystemClock.elapsedRealtime() }

    // A fresh HEIC verdict each time, so every self test really probes (the app shares one per process).
    private fun real(policy: FailurePolicy = policy(), heic: HeicVerdict = HeicVerdict()) = DecoderClient(context, policy = policy, heicVerdict = heic)

    private fun test(service: Class<*>, policy: FailurePolicy = policy(), timeouts: DecoderTimeouts = DecoderTimeouts.DEFAULT) =
        DecoderClient(context, ComponentName(context, service), timeouts, policy, ":decodertest", HeicVerdict())

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun asset(name: String) = file(name, instrumentation.context.assets.open(name).use { it.readBytes() })

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 100): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /** 401 x 301 with a gradient and a transparent band. */
    private fun gradient(w: Int = 401, h: Int = 301, alpha: Boolean = true): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (alpha && y < h / 3) (x * 255 / w) else 255
            setPixel(x, y, Color.argb(a, x * 255 / w, y * 255 / h, (x + y) and 255))
        }
    }

    private fun inProcess(f: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals("size", expected.width to expected.height, actual.width to actual.height)
        val a = IntArray(expected.width * expected.height).also { expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height) }
        val b = IntArray(actual.width * actual.height).also { actual.getPixels(it, 0, actual.width, 0, 0, actual.width, actual.height) }
        assertArrayEquals(a, b)
    }

    private fun status(block: () -> Unit): DecodeStatus = try {
        block()
        DecodeStatus.OK
    } catch (e: DecodeFailure) {
        e.status
    }

    private fun processes(): List<String> {
        val pfd = instrumentation.uiAutomation.executeShellCommand("ps -A -o NAME")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readLines()
    }

    /** True for a process named [name] or an instance of it ([name]:something). */
    private fun named(line: String, name: String) = line.trim().let { it == name || it.startsWith("$name:") }

    /** Polls ps until no process is named [name], for at most [ms]. */
    private fun assertGone(name: String, ms: Long = 5_000) {
        val deadline = SystemClock.elapsedRealtime() + ms
        var left = processes().filter { named(it, name) }
        while (left.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            left = processes().filter { named(it, name) }
        }
        assertTrue("still running after unbind: $left", left.isEmpty())
    }

    private fun assertReaderStillWorks() {
        val t = real().selfTest()
        assertTrue("the real reader still starts sealed: $t", t.sealed)
    }

    private fun png(): File = file("good.png", encode(gradient(), Bitmap.CompressFormat.PNG))

    // 1
    @Test
    fun isolation() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, IsolatedDecoderService::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertTrue("isolatedProcess", info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertFalse("exported", info.exported)
        assertTrue("process ${info.processName}", info.processName.endsWith(":decoder"))
        val start = SystemClock.elapsedRealtime()
        val t = real().selfTest()
        Log.i(TAG, "selfTest: $t in ${SystemClock.elapsedRealtime() - start} ms")
        assertTrue("isolated: $t", t.isolated)
        assertTrue("no app data: $t", t.noAppData)
        assertTrue("heic answered: $t", t.heic != null)
    }

    // 2
    @Test
    fun pngEqualsInProcess() {
        val f = png()
        val via = real().decodeImage(f, ImageKind.PNG)
        assertEquals(401 to 301, via.width to via.height)
        assertTrue(via.hasAlpha())
        assertSamePixels(inProcess(f), via)
    }

    // 3
    @Test
    fun jpegAndLosslessWebpEqualInProcess() {
        val jpeg = file("photo.jpg", encode(gradient(alpha = false), Bitmap.CompressFormat.JPEG, 90))
        assertSamePixels(inProcess(jpeg), real().decodeImage(jpeg, ImageKind.JPEG))
        val webp = file("pic.webp", encode(gradient(), Bitmap.CompressFormat.WEBP_LOSSLESS))
        assertSamePixels(inProcess(webp), real().decodeImage(webp, ImageKind.WEBP))

        val turned = file("turned.jpg", encode(gradient(400, 300, alpha = false), Bitmap.CompressFormat.JPEG, 90))
        ExifInterface(turned.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val via = real().decodeImage(turned, ImageKind.JPEG)
        assertEquals("EXIF orientation 6 applied", 300 to 400, via.width to via.height)
        assertSamePixels(inProcess(turned), via)
    }

    // 4
    @Test
    fun aLargeImageIsSampled() {
        val big = Bitmap.createBitmap(6000, 4000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 90, 160))
            Canvas(this).drawCircle(3000f, 2000f, 1500f, Paint().apply { color = Color.YELLOW })
        }
        val f = file("big.png", encode(big, Bitmap.CompressFormat.PNG))
        big.recycle()
        val via = real().decodeImage(f, ImageKind.PNG)
        assertTrue("${via.width} x ${via.height}", via.width.toLong() * via.height <= 16_000_000 && via.width <= 16_384 && via.height <= 16_384)
        assertTrue("aspect ${via.width} x ${via.height}", abs(via.width * 4000L / 6000 - via.height) <= 1)
    }

    private fun pdf(pages: Int): File {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 28f; isAntiAlias = true }
        for (n in 1..pages) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            page.canvas.apply {
                drawText("Page $n of $pages: the sealed decoder", 60f, 100f, paint.apply { color = Color.BLACK })
                drawRect(60f, 160f, 300f + n * 5, 400f, paint.apply { color = Color.rgb(200, 30, 30) })
                drawCircle(400f, 600f, 80f, paint.apply { color = Color.rgb(20, 120, 60) })
            }
            doc.finishPage(page)
        }
        return File(dir, "doc$pages.pdf").also { f -> f.outputStream().use { doc.writeTo(it) }; doc.close() }
    }

    private fun renderInProcess(f: File, index: Int): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(index).use { page ->
                val limits = FrameLimits.PAGE
                val (w, h) = FrameSizing.renderSize(page.width, page.height, FrameLimits.PDF_DPI, limits.maxPixels, limits.maxSide)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    setHasAlpha(false)
                }
            }
        }

    // 5
    @Test
    fun pdfPageEqualsInProcess() {
        val three = pdf(3)
        val client = real()
        client.openPdf(three).use { job ->
            assertEquals(3, job.pageCount)
            assertSamePixels(renderInProcess(three, 1), job.renderPage(1))
        }

        val thirty = pdf(30)
        val one = real()
        one.openPdf(thirty).use { job ->
            assertEquals(30, job.pageCount)
            // While the job is open, its sealed process runs under the decoder's process name.
            val running = processes().filter { named(it, "${context.packageName}:decoder") }
            Log.i(TAG, "decoder processes during a PDF job: $running")
            assertTrue("a ${context.packageName}:decoder process during the job", running.isNotEmpty())
            for (i in 0 until 30) {
                val page = job.renderPage(i)
                assertEquals(1239 to 1754, page.width to page.height)
                assertFalse(page.hasAlpha())
            }
        }
        assertEquals("one process for the whole PDF", 1, one.binds.get())
    }

    // 6
    @Test
    fun truncatedWebpIsDamagedAndTheNextFileDecodes() {
        val client = real()
        assertEquals(DecodeStatus.DAMAGED, status { client.decodeImage(asset("truncated.webp"), ImageKind.WEBP) })
        assertGone("${context.packageName}:decoder")
        assertEquals(401, client.decodeImage(png(), ImageKind.PNG).width)
    }

    // 7
    @Test
    fun truncatedPdfIsDamagedAtOpen() {
        assertEquals(DecodeStatus.DAMAGED, status { real().openPdf(asset("truncated.pdf")).close() })
    }

    /**
     * A PNG whose IHDR claims [w] x [h] (CRC computed), followed by a tiny IDAT and IEND. RGBA by default; 1-bit grey keeps
     * a big header's byte size small, so no codec limit on bytes refuses it before the service's own check sees it.
     */
    private fun pngHeader(w: Int, h: Int, bitDepth: Int = 8, colourType: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val td = type.toByteArray(Charsets.US_ASCII) + data
            out.write(td)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(td) }.value.toInt()).array())
        }
        // Bit depth, colour type, deflate, adaptive filtering, no interlace.
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(byteArrayOf(bitDepth.toByte(), colourType.toByte(), 0, 0, 0)).array())
        val deflater = Deflater().apply { setInput(ByteArray(64)); finish() }
        val z = ByteArray(128).let { buf -> buf.copyOf(deflater.deflate(buf)) }
        chunk("IDAT", z)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    // 8
    @Test
    fun aHugePngHeaderIsTooLarge() {
        // 100000 x 100000 is over the source pixel cap. A codec may also refuse a header that size itself (DAMAGED);
        // either way nothing is allocated and nothing crashes.
        val huge = status { real().decodeImage(file("huge.png", pngHeader(100_000, 100_000)), ImageKind.PNG) }
        Log.i(TAG, "100000 x 100000 PNG header: $huge")
        assertTrue("$huge", huge == DecodeStatus.TOO_LARGE || huge == DecodeStatus.DAMAGED)
        // 70000 x 10 is small enough for every codec but over the side cap: only the service's own check refuses it.
        assertEquals(DecodeStatus.TOO_LARGE, status { real().decodeImage(file("wide.png", pngHeader(70_000, 10)), ImageKind.PNG) })
        // The 2^28 pixel cap, on 1-bit grey headers (16384 x 16384 is 32 MiB of rows, so no codec refuses it first):
        // over it is TOO_LARGE; exactly at it passes the check and then fails on the missing pixel data (DAMAGED).
        assertEquals(DecodeStatus.TOO_LARGE, status { real().decodeImage(file("cap1.png", pngHeader(20_000, 20_000, 1, 0)), ImageKind.PNG) })
        assertEquals(DecodeStatus.TOO_LARGE, status { real().decodeImage(file("cap2.png", pngHeader(16_385, 16_384, 1, 0)), ImageKind.PNG) })
        assertEquals(DecodeStatus.DAMAGED, status { real().decodeImage(file("cap3.png", pngHeader(16_384, 16_384, 1, 0)), ImageKind.PNG) })
        assertReaderStillWorks()
    }

    // 9
    @Test
    fun theWrongKindIsDamaged() {
        assertEquals(DecodeStatus.DAMAGED, status { real().decodeImage(png(), ImageKind.JPEG) })
    }

    // 10
    @Test
    fun heicAnswersAgree() {
        val verdict = HeicVerdict()
        val t = real(heic = verdict).selfTest()
        // Whatever canDecode did (on the API 36 emulator the sealed process dies on HEIC), no reader process is left.
        assertGone("${context.packageName}:decoder")
        val heic = asset("gradient.heic")
        val decoded = try {
            real().decodeImage(heic, ImageKind.HEIC)
        } catch (e: DecodeFailure) {
            Log.i(TAG, "HEIC in the sealed process: canDecode ${t.heicStatus}, decodeImage ${e.status}")
            // The API 36 emulator's sealed process dies decoding the built-in sample (CRASHED): HEIC is then refused,
            // never decoded anywhere else. A timeout or any other answer still fails.
            assertTrue("canDecode says this process can't read HEIC: ${t.heicStatus}", t.heicStatus == DecodeStatus.UNSUPPORTED || t.heicStatus == DecodeStatus.CRASHED)
            assertEquals(DecodeStatus.UNSUPPORTED, e.status)
            // The refusal is remembered for the process: the next HEIC file is refused without starting a reader.
            val again = real(heic = verdict)
            assertEquals(DecodeStatus.UNSUPPORTED, status { again.decodeImage(heic, ImageKind.HEIC) })
            assertEquals("refused from memory, without binding", 0, again.binds.get())
            assertEquals("Device checks reads the remembered refusal", t.heicStatus, real(heic = verdict).selfTest().heicStatus)
            return
        }
        Log.i(TAG, "HEIC in the sealed process: canDecode ${t.heicStatus}, decodeImage OK")
        assertEquals("canDecode and decodeImage agree", DecodeStatus.OK, t.heicStatus)
        assertEquals(64 to 48, decoded.width to decoded.height)
    }

    // 11
    @Test
    fun aCrashIsCrashedAndTheThirdTryIsRefusedWithoutBinding() {
        val policy = policy()
        val f = png()
        for (attempt in 1..2) {
            val client = test(CrashingDecoderService::class.java, policy)
            val start = SystemClock.elapsedRealtime()
            assertEquals("attempt $attempt", DecodeStatus.CRASHED, status { client.decodeImage(f, ImageKind.PNG) })
            assertTrue("within 5 s", SystemClock.elapsedRealtime() - start < 5_000)
        }
        val third = test(CrashingDecoderService::class.java, policy)
        val start = SystemClock.elapsedRealtime()
        assertEquals(DecodeStatus.REFUSED_FILE, status { third.decodeImage(f, ImageKind.PNG) })
        assertTrue("refused at once", SystemClock.elapsedRealtime() - start < 500)
        assertEquals("refused without binding", 0, third.binds.get())
        assertReaderStillWorks()
    }

    // 12
    @Test
    fun aHangIsATimeoutAndUnbindingKillsTheProcess() {
        val client = test(HangingDecoderService::class.java, timeouts = DecoderTimeouts(workCallMs = 2_000))
        val start = SystemClock.elapsedRealtime()
        assertEquals(DecodeStatus.TIMEOUT, status { client.decodeImage(png(), ImageKind.PNG) })
        val took = SystemClock.elapsedRealtime() - start
        assertTrue("TIMEOUT after $took ms", took in 2_000..6_000)
        assertGone("${context.packageName}:decodertest")
        assertReaderStillWorks()
    }

    // 13
    @Test
    fun abortIsCancelledAndNotCounted() {
        val policy = policy()
        val f = png()
        repeat(2) {
            val client = test(HangingDecoderService::class.java, policy)
            Thread {
                Thread.sleep(300)
                client.abort()
            }.start()
            val start = SystemClock.elapsedRealtime()
            assertEquals(DecodeStatus.CANCELLED, status { client.decodeImage(f, ImageKind.PNG) })
            assertTrue("cancelled promptly", SystemClock.elapsedRealtime() - start < 5_000)
            // Sticky: the aborted client takes no new job.
            assertEquals(DecodeStatus.CANCELLED, status { client.decodeImage(f, ImageKind.PNG) })
        }
        assertNull("aborts are not counted", policy.refusal(f.canonicalPath))
        assertReaderStillWorks()
    }

    // 14
    @Test
    fun aLyingServiceIsABadReplyAndTheRegionIsNeverReadForIt() {
        val policy = policy()
        val f = png()
        val oversized = test(LyingDecoderService::class.java, policy)
        assertEquals(DecodeStatus.BAD_REPLY, status { oversized.decodeImage(f, ImageKind.PNG) })
        assertEquals("never mapped", 0, oversized.regionMaps.get())

        val stride = test(LyingDecoderService::class.java, policy)
        assertEquals(DecodeStatus.BAD_REPLY, status { stride.decodeImage(file("other.png", f.readBytes()), ImageKind.JPEG) })
        assertEquals("never mapped", 0, stride.regionMaps.get())

        val unprotected = test(LyingDecoderService::class.java, policy)
        val third = status { unprotected.decodeImage(file("third.png", f.readBytes()), ImageKind.WEBP) }
        Log.i(TAG, "a region with its protection taken away: $third")
        // Strict: on the API 36 emulator the service can take the protection away and the host's mapping then fails.
        assertEquals(DecodeStatus.BAD_REPLY, third)
        assertEquals("never read", 0, unprotected.regionMaps.get())

        // Exception headers the generated proxy would have acted on (EX_PARCELABLE, a StrictMode reply header): the host
        // reads the first int only, so each is BAD_REPLY and nothing of the service's is unparcelled or thrown here.
        // Their own policy: five counted bad replies on one policy would pause the reader for the rest of this test.
        val headers = policy()
        val parcelable = test(ExceptionHeaderDecoderService::class.java, headers)
        assertEquals(DecodeStatus.BAD_REPLY, status { parcelable.decodeImage(file("exc1.png", f.readBytes()), ImageKind.PNG) })
        val strictMode = test(ExceptionHeaderDecoderService::class.java, headers)
        assertEquals(DecodeStatus.BAD_REPLY, status { strictMode.decodeImage(file("exc2.png", f.readBytes()), ImageKind.JPEG) })
        assertEquals("never read", 0, parcelable.regionMaps.get() + strictMode.regionMaps.get())

        // Counted: the first file's second bad reply refuses it.
        assertEquals(DecodeStatus.BAD_REPLY, status { test(LyingDecoderService::class.java, policy).decodeImage(f, ImageKind.PNG) })
        assertEquals(DecodeStatus.REFUSED_FILE, policy.refusal(f.canonicalPath))
        assertReaderStillWorks()
    }

    // 15
    @Test
    fun theAppIsUnaffectedByCrashesHangsAndAborts() {
        val f = png()
        assertEquals(DecodeStatus.CRASHED, status { test(CrashingDecoderService::class.java).decodeImage(f, ImageKind.PNG) })
        val fast = DecoderTimeouts(workCallMs = 1_000)
        assertEquals(DecodeStatus.TIMEOUT, status { test(HangingDecoderService::class.java, timeouts = fast).decodeImage(f, ImageKind.PNG) })
        val aborted = test(HangingDecoderService::class.java)
        Thread {
            Thread.sleep(300)
            aborted.abort()
        }.start()
        assertEquals(DecodeStatus.CANCELLED, status { aborted.decodeImage(f, ImageKind.PNG) })
        assertReaderStillWorks()
        assertEquals(401, real().decodeImage(f, ImageKind.PNG).width)
    }

    @Test
    fun theHandWrittenProxyUsesTheStubsDescriptor() {
        val stub = object : TestDecoderStub() {
            override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply =
                reply(DecodeStatus.BAD_REQUEST)
        }
        assertEquals(stub.interfaceDescriptor, RawDecoder.DESCRIPTOR)
    }

    @Test
    fun anAbortJustBeforeTheBindLeavesNoProcess() {
        val client = test(HangingDecoderService::class.java)
        // The race: abort() lands after the job registered its session and before it binds.
        client.beforeBindForTest = { client.abort() }
        assertEquals(DecodeStatus.CANCELLED, status { client.decodeImage(png(), ImageKind.PNG) })
        assertEquals("never bound", 0, client.binds.get())
        assertGone("${context.packageName}:decodertest")
    }

    @Test
    fun aSelfTestDuringAConversionIsBusy() {
        real().openPdf(pdf(3)).use {
            val start = SystemClock.elapsedRealtime()
            val t = real().selfTest()
            assertTrue("busy: $t", t.busy)
            assertTrue("answered without waiting for the conversion", SystemClock.elapsedRealtime() - start < 5_000)
        }
        assertFalse(real().selfTest().busy)
    }

    @Test
    fun theMainThreadIsRefused() {
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            error = runCatching { real().decodeImage(png(), ImageKind.PNG) }.exceptionOrNull()
        }
        assertTrue("$error", error is IllegalStateException)
    }

    @Test
    fun aSecondJobWaitsForTheFirst() {
        val thirty = pdf(30)
        val busy = DecoderClient(context, timeouts = DecoderTimeouts(busyWaitMs = 500), policy = policy())
        real().openPdf(thirty).use {
            assertEquals(DecodeStatus.BUSY, status { busy.decodeImage(png(), ImageKind.PNG) })
        }
        assertEquals(401, busy.decodeImage(png(), ImageKind.PNG).width)
    }

    private companion object {
        const val TAG = "DecoderClientTest"
    }
}
