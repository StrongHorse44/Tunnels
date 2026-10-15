package io.github.stronghorse44.tunnels.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import io.github.stronghorse44.tunnels.decoder.ReplyCheck.FrameResult
import io.github.stronghorse44.tunnels.decoder.ReplyCheck.PdfOpenResult
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A decode that did not produce a picture. Never carries a message from the service: only the host's own status. */
class DecodeFailure(val status: DecodeStatus) : Exception(status.name)

/** The PDF has more pages than Tunnels renders; nothing was rendered. */
class TooManyPagesException(val pageCount: Int, val maxPages: Int) : Exception("too many pages")

/** What Device checks shows. [heic] is null when the reader didn't start; [heicStatus] says how canDecode(HEIC) ended. */
data class SelfTest(val isolated: Boolean, val noAppData: Boolean, val heic: Boolean?, val heicStatus: DecodeStatus?) {
    val sealed: Boolean get() = isolated && noAppData
}

/** One PDF job: one sealed process from open to close, every page rendered in it. */
interface PdfJob : AutoCloseable {
    val pageCount: Int

    /** A host-owned opaque ARGB_8888 bitmap of page [index] (from 0). Throws DecodeFailure. */
    fun renderPage(index: Int): Bitmap

    override fun close()
}

/**
 * The host side of the sealed decoder (audit M6). Blocking; never call it on the main thread.
 *
 * Every job binds a new isolated process (a fresh instance name), checks that it is sealed before it sends a file,
 * and kills it by unbinding when the job ends. Every value the service returns is checked before the host reads a byte
 * of the frame region. There is no in-process fallback anywhere: when the sealed reader is unavailable, the file is
 * refused. Nothing retries on its own; [policy] stops a file (and then the reader) that keeps breaking it.
 *
 * [abort] ends the current job from any thread and makes every later call on this client fail with CANCELLED: one
 * client serves one conversion.
 */
class DecoderClient(
    context: Context,
    private val component: ComponentName = ComponentName(context, IsolatedDecoderService::class.java),
    private val timeouts: DecoderTimeouts = DecoderTimeouts.DEFAULT,
    private val policy: FailurePolicy = SHARED_POLICY,
    private val processSuffix: String = PROCESS_SUFFIX,
) {
    private val app: Context = context.applicationContext
    private val sessions = CopyOnWriteArraySet<Session>()

    @Volatile
    private var aborted = false

    /** Test hooks: how many times this client bound a service, and mapped a frame region to read it. */
    internal val binds = AtomicInteger()
    internal val regionMaps = AtomicInteger()

    /** A host-owned software ARGB_8888 bitmap within [limits]. Throws DecodeFailure. */
    fun decodeImage(file: File, kind: ImageKind, limits: FrameLimits = FrameLimits.IMAGE): Bitmap {
        val key = admit(file)
        acquire(timeouts.busyWaitMs)
        try {
            return counted(key) {
                val s = start()
                try {
                    if (kind == ImageKind.HEIC) requireHeic(s)
                    val region = s.region()
                    val source = s.source(file)
                    val reply = s.call(timeouts.workCallMs) { it.decodeImage(source, kind.wire, FrameRegion(region), limits.maxPixels, limits.maxSide) }
                    val frame = frameOf(ReplyCheck.frame(reply.wire(), limits, FrameLimits.REGION_BYTES))
                    // The job is done: the process is killed before the host reads its output.
                    s.stop()
                    s.read(frame)
                } finally {
                    s.end()
                }
            }
        } finally {
            JOBS.release()
        }
    }

    /** One job for the whole PDF; close() ends it. Throws DecodeFailure, or TooManyPagesException over [maxPages]. */
    fun openPdf(file: File, maxPages: Int = FrameLimits.MAX_PDF_PAGES): PdfJob {
        val key = admit(file)
        acquire(timeouts.busyWaitMs)
        var handedOver = false
        try {
            return counted(key) {
                val s = start()
                try {
                    s.region()
                    val source = s.source(file)
                    val reply = s.call(timeouts.workCallMs) { it.openPdf(source) }
                    when (val opened = ReplyCheck.pdfOpen(reply.wire(), maxPages)) {
                        is PdfOpenResult.Opened -> Pages(key, s, opened.pageCount).also { handedOver = true }
                        is PdfOpenResult.TooManyPages -> throw TooManyPagesException(opened.pageCount, maxPages)
                        is PdfOpenResult.Failed -> throw DecodeFailure(opened.status)
                    }
                } finally {
                    if (!handedOver) s.end()
                }
            }
        } finally {
            if (!handedOver) JOBS.release()
        }
    }

    /**
     * Binds a fresh reader and asks it whether it is sealed and whether it can read HEIC, then ends it. Within
     * [DecoderTimeouts.selfTestMs] in all; sends no file and never throws.
     */
    fun selfTest(): SelfTest {
        checkWorker()
        val none = SelfTest(isolated = false, noAppData = false, heic = null, heicStatus = null)
        val deadline = SystemClock.elapsedRealtime() + timeouts.selfTestMs
        fun left(cap: Long): Long = minOf(cap, deadline - SystemClock.elapsedRealtime())
        if (!declaredSealed()) return none
        try {
            acquire(left(timeouts.busyWaitMs))
        } catch (_: DecodeFailure) {
            return none
        }
        try {
            val s = session()
            try {
                s.bind(left(timeouts.bindMs))
                if (s.call(left(timeouts.quickCallMs)) { it.protocolVersion() } != PROTOCOL_VERSION) return none
                val bits = s.call(left(timeouts.quickCallMs)) { it.selfCheck() }
                val heic = try {
                    ReplyCheck.canDecode(s.call(left(timeouts.probeCallMs)) { it.canDecode(ImageKind.HEIC.wire) })
                } catch (e: DecodeFailure) {
                    e.status
                }
                return SelfTest(SelfCheckBits.isolated(bits), SelfCheckBits.noAppData(bits), heic == DecodeStatus.OK, heic)
            } catch (_: DecodeFailure) {
                return none
            } finally {
                s.end()
            }
        } finally {
            JOBS.release()
        }
    }

    /** Ends the current job now, from any thread; idempotent. Every later call on this client fails with CANCELLED. */
    fun abort() {
        aborted = true
        sessions.forEach { it.stop() }
    }

    // ---- Job steps ----

    /** Before anything is bound: not on the main thread, not cancelled, not refused by the policy, not too big. */
    private fun admit(file: File): String {
        checkWorker()
        if (aborted) throw DecodeFailure(DecodeStatus.CANCELLED)
        val key = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }
        policy.refusal(key)?.let { fail(it) }
        if (!file.isFile) fail(DecodeStatus.DAMAGED)
        if (file.length() > FrameLimits.MAX_SOURCE_BYTES) fail(DecodeStatus.TOO_LARGE)
        return key
    }

    /** Runs a job on [key]; a failure is logged (its status only) and counted when the policy counts it. */
    private fun <T> counted(key: String, block: () -> T): T = try {
        block()
    } catch (e: DecodeFailure) {
        policy.record(key, e.status)
        Log.w(TAG, "decoder: ${e.status.name}")
        throw e
    }

    /** The manifest check, a fresh process, and its own word that it is sealed, before any file is sent. */
    private fun start(): Session {
        if (!declaredSealed()) fail(DecodeStatus.UNAVAILABLE)
        val s = session()
        try {
            s.bind(timeouts.bindMs)
            if (s.call(timeouts.quickCallMs) { it.protocolVersion() } != PROTOCOL_VERSION) fail(DecodeStatus.UNAVAILABLE)
            if (!SelfCheckBits.sealed(s.call(timeouts.quickCallMs) { it.selfCheck() })) fail(DecodeStatus.UNAVAILABLE)
            return s
        } catch (e: DecodeFailure) {
            s.end()
            // No file has been sent: whatever went wrong, the reader didn't start (and the file is not to blame).
            throw DecodeFailure(if (e.status == DecodeStatus.CANCELLED) DecodeStatus.CANCELLED else DecodeStatus.UNAVAILABLE)
        }
    }

    /** HEIC is decoded only when this process decodes the built-in sample; otherwise it is refused (RJ's M6-Q1). */
    private fun requireHeic(s: Session) {
        val answer = try {
            ReplyCheck.canDecode(s.call(timeouts.probeCallMs) { it.canDecode(ImageKind.HEIC.wire) })
        } catch (e: DecodeFailure) {
            if (e.status == DecodeStatus.CANCELLED) throw e
            DecodeStatus.UNSUPPORTED
        }
        if (answer != DecodeStatus.OK) fail(DecodeStatus.UNSUPPORTED)
    }

    /** The service must be ours, declared isolated, not exported, in its own `:decoder` process. */
    private fun declaredSealed(): Boolean {
        if (component.packageName != app.packageName) return false
        val info = try {
            app.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } catch (_: Exception) {
            return false
        }
        return info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0 && !info.exported && info.processName?.endsWith(processSuffix) == true
    }

    /** One job at a time, process-wide; waits at most [maxWaitMs], and gives up early when this client is aborted. */
    private fun acquire(maxWaitMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            if (aborted) fail(DecodeStatus.CANCELLED)
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) fail(DecodeStatus.BUSY)
            try {
                if (JOBS.tryAcquire(minOf(left, 200L), TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(DecodeStatus.CANCELLED)
            }
        }
    }

    private fun session(): Session {
        val s = Session()
        sessions.add(s)
        if (aborted) {
            s.end()
            fail(DecodeStatus.CANCELLED)
        }
        return s
    }

    private fun frameOf(result: FrameResult): FrameResult.Frame = when (result) {
        is FrameResult.Frame -> result
        is FrameResult.Failed -> fail(result.status)
    }

    private fun checkWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "The sealed decoder blocks: call it off the main thread." }
    }

    private fun fail(status: DecodeStatus): Nothing = throw DecodeFailure(status)

    private fun DecodeReply?.wire(): WireReply =
        if (this == null) WireReply(status = -1) else WireReply(status, width, height, stride, colorSpace, flags, pageCount)

    // ---- One sealed process ----

    private inner class Pages(private val key: String, private val s: Session, override val pageCount: Int) : PdfJob {
        private val closed = AtomicBoolean(false)

        @Volatile
        private var failed = false

        override fun renderPage(index: Int): Bitmap {
            checkWorker()
            if (closed.get() || failed) fail(DecodeStatus.CANCELLED)
            if (index !in 0 until pageCount) fail(DecodeStatus.BAD_REQUEST)
            try {
                return counted(key) {
                    val region = s.region()
                    val reply = s.call(timeouts.workCallMs) {
                        it.renderPdfPage(index, FrameLimits.PDF_DPI, FrameRegion(region), FrameLimits.PAGE.maxPixels, FrameLimits.PAGE.maxSide)
                    }
                    s.read(frameOf(ReplyCheck.frame(reply.wire(), FrameLimits.PAGE, FrameLimits.REGION_BYTES))).apply { setHasAlpha(false) }
                }
            } catch (e: DecodeFailure) {
                failed = true
                close()
                throw e
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                if (!failed && s.alive) runCatching { s.call(timeouts.closeMs) { it.closePdf() } }
            } finally {
                s.end()
                JOBS.release()
            }
        }
    }

    private inner class Session {
        private val connected = CompletableFuture<IBinder>()
        private val stopped = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val death = IBinder.DeathRecipient { dead = true }

        @Volatile
        private var dead = false

        @Volatile
        private var pending: Future<*>? = null
        private var bound = false
        private var binder: IBinder? = null
        private var remote: IIsolatedDecoder? = null
        private var memory: SharedMemory? = null
        private var sourceFd: ParcelFileDescriptor? = null

        val alive: Boolean get() = !stopped.get() && !dead

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) connected.completeExceptionally(DecodeFailure(DecodeStatus.UNAVAILABLE)) else connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                dead = true
                connected.completeExceptionally(DecodeFailure(DecodeStatus.UNAVAILABLE))
            }

            override fun onBindingDied(name: ComponentName?) {
                dead = true
                connected.completeExceptionally(DecodeFailure(DecodeStatus.UNAVAILABLE))
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.completeExceptionally(DecodeFailure(DecodeStatus.UNAVAILABLE))
            }
        }

        /** A new isolated process: the instance name is a counter, never a file name (process names are visible to ps). */
        fun bind(timeoutMs: Long) {
            if (timeoutMs <= 0) fail(DecodeStatus.UNAVAILABLE)
            binds.incrementAndGet()
            bound = true
            val ok = try {
                app.bindIsolatedService(Intent().setComponent(component), Context.BIND_AUTO_CREATE, "j" + INSTANCES.incrementAndGet(), CALLBACKS, connection)
            } catch (_: Exception) {
                false
            }
            if (!ok) fail(DecodeStatus.UNAVAILABLE)
            val b = try {
                connected.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                fail(DecodeStatus.UNAVAILABLE)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(DecodeStatus.CANCELLED)
            } catch (_: Exception) {
                fail(if (aborted) DecodeStatus.CANCELLED else DecodeStatus.UNAVAILABLE)
            }
            if (aborted || stopped.get()) fail(DecodeStatus.CANCELLED)
            try {
                b.linkToDeath(death, 0)
            } catch (_: RemoteException) {
                fail(DecodeStatus.UNAVAILABLE)
            }
            binder = b
            val svc = IIsolatedDecoder.Stub.asInterface(b)
            // A local Stub means the service runs in this very process: not sealed.
            if (svc == null || svc is IIsolatedDecoder.Stub) fail(DecodeStatus.UNAVAILABLE)
            remote = svc
        }

        /**
         * One remote call on a worker thread, waited for at most [timeoutMs]. A timeout or an abort ends the process at
         * once (the worker may stay blocked until it dies; it holds no lock). Any exception becomes a status: a dead
         * process is CRASHED, anything else (a malformed reply, an exception header with text) is BAD_REPLY, and its
         * message is neither shown nor logged.
         */
        fun <T> call(timeoutMs: Long, block: (IIsolatedDecoder) -> T): T {
            val svc = remote
            if (aborted || stopped.get() || svc == null) fail(DecodeStatus.CANCELLED)
            if (timeoutMs <= 0) {
                stop()
                fail(DecodeStatus.TIMEOUT)
            }
            val f = WORKERS.submit(Callable { block(svc) })
            pending = f
            if (aborted || stopped.get()) f.cancel(true)
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                f.cancel(true)
                stop()
                fail(if (aborted) DecodeStatus.CANCELLED else DecodeStatus.TIMEOUT)
            } catch (_: CancellationException) {
                stop()
                fail(if (aborted || stopped.get()) DecodeStatus.CANCELLED else DecodeStatus.CRASHED)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                f.cancel(true)
                stop()
                fail(DecodeStatus.CANCELLED)
            } catch (e: ExecutionException) {
                val wasDead = dead
                stop()
                fail(
                    when {
                        aborted -> DecodeStatus.CANCELLED
                        wasDead || e.cause is RemoteException -> DecodeStatus.CRASHED
                        else -> DecodeStatus.BAD_REPLY
                    },
                )
            } finally {
                pending = null
            }
        }

        /** The host-created region, once per job, fixed size. */
        fun region(): SharedMemory = memory ?: try {
            SharedMemory.create("tunnels-frame", FrameLimits.REGION_BYTES).also { memory = it }
        } catch (_: Exception) {
            fail(DecodeStatus.UNAVAILABLE)
        }

        /** The staged file, read-only, opened by the host for this job. The host never reads its own copy while the job runs. */
        fun source(file: File): ParcelFileDescriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { sourceFd = it }
        } catch (_: IOException) {
            fail(DecodeStatus.DAMAGED)
        }

        /**
         * Copies a checked frame out of the region into a new host-owned bitmap. The region is mapped read-write (the host
         * writes nothing): a service that took away the region's protection, on ashmem or on a sealed memfd, makes this
         * mapping fail, which is BAD_REPLY, instead of the host reading a region it no longer controls.
         */
        fun read(frame: FrameResult.Frame): Bitmap {
            val r = memory ?: fail(DecodeStatus.INTERNAL)
            val buf = try {
                r.mapReadWrite()
            } catch (_: Exception) {
                fail(DecodeStatus.BAD_REPLY)
            }
            regionMaps.incrementAndGet()
            try {
                buf.position(0)
                buf.limit(frame.byteCount)
                val space = ColorSpace.get(if (frame.color == FrameColor.DISPLAY_P3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
                val bitmap = try {
                    Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888, frame.hasAlpha, space)
                } catch (_: OutOfMemoryError) {
                    fail(DecodeStatus.TOO_LARGE)
                }
                try {
                    bitmap.copyPixelsFromBuffer(buf)
                } catch (_: RuntimeException) {
                    bitmap.recycle()
                    fail(DecodeStatus.BAD_REPLY)
                }
                return bitmap
            } finally {
                SharedMemory.unmap(buf)
            }
        }

        /** Unbinds, so the system kills the process. Any thread; idempotent. The region stays until [end]. */
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            pending?.cancel(true)
            connected.completeExceptionally(DecodeFailure(DecodeStatus.CANCELLED))
            binder?.let { b -> runCatching { b.unlinkToDeath(death, 0) } }
            if (bound) runCatching { app.unbindService(connection) }
        }

        /** Stops the process and closes the region and the source. Called by the job's own thread. */
        fun end() {
            stop()
            if (!released.compareAndSet(false, true)) return
            runCatching { sourceFd?.close() }
            runCatching { memory?.close() }
            sessions.remove(this)
        }
    }

    companion object {
        private const val TAG = "TunnelsDecoder"

        /** The process the sealed decoder must be declared in (with the package name before it). */
        const val PROCESS_SUFFIX = ":decoder"

        /** Process-wide and in memory only: restarting Tunnels clears it. */
        val SHARED_POLICY = FailurePolicy { SystemClock.elapsedRealtime() }

        private val JOBS = Semaphore(1, true)
        private val INSTANCES = AtomicLong()

        private fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        private val WORKERS = Executors.newCachedThreadPool(daemon("tunnels-decoder-call"))
        private val CALLBACKS = Executors.newSingleThreadExecutor(daemon("tunnels-decoder-bind"))
    }
}
