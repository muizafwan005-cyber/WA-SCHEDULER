package com.wascheduler.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mengantre dan menjalankan pengiriman satu per satu.
 * Semua dipanggil dari main thread.
 */
object SendCoordinator {

    class Job(
        val scheduleId: String,
        val isGroup: Boolean,
        val target: String,
        val pkg: String
    ) {
        var chosen = false      // grup sudah dipilih di daftar
        var clicks = 0          // berapa kali tombol kirim ditekan
        var lastClick = 0L
        var typedAt = 0L        // kapan nama grup diketik di kolom cari
    }

    private const val TIMEOUT_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var appCtx: Context? = null

    @Volatile
    var current: Job? = null
        private set

    private val timeout = Runnable { finish(false, "Waktu habis, tombol kirim tidak ditemukan") }

    private val ticker = object : Runnable {
        override fun run() {
            if (current == null) return
            WaAccessibilityService.instance?.step()
            handler.postDelayed(this, 800)
        }
    }

    fun enqueue(ctx: Context, id: String) {
        appCtx = ctx.applicationContext
        queue.addLast(id)
        if (current == null) handler.post { startNext() }
    }

    private fun startNext() {
        if (current != null) return
        val ctx = appCtx ?: return
        val id = queue.removeFirstOrNull() ?: return
        val s = Store.get(ctx, id)
        if (s == null) {
            startNext()
            return
        }

        val pkg = WaUtil.installedPackage(ctx)
        if (pkg == null) {
            record(ctx, s, false, "WhatsApp tidak terpasang")
            startNext()
            return
        }
        val service = WaAccessibilityService.instance
        if (service == null) {
            record(ctx, s, false, "Layanan aksesibilitas belum aktif")
            startNext()
            return
        }

        current = Job(s.id, s.isGroup, s.target, pkg)
        WaUtil.wakeScreen(ctx)

        try {
            service.startActivity(WaUtil.buildIntent(ctx, s, pkg))
        } catch (e: Exception) {
            finish(false, "Gagal membuka WhatsApp: ${e.message}")
            return
        }

        handler.postDelayed(timeout, TIMEOUT_MS)
        handler.postDelayed(ticker, 1500)
    }

    fun finish(ok: Boolean, msg: String) {
        val job = current ?: return
        current = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(ticker)

        val ctx = appCtx
        if (ctx != null) {
            Store.get(ctx, job.scheduleId)?.let { record(ctx, it, ok, msg) }
            Toast.makeText(
                ctx,
                if (ok) "Pesan terkirim" else "Gagal mengirim: $msg",
                Toast.LENGTH_LONG
            ).show()
        }

        WaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        handler.postDelayed({ startNext() }, 2000)
    }

    private fun record(ctx: Context, s: Schedule, ok: Boolean, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date())
        s.lastStatus = if (ok) "$t - Terkirim" else "$t - Gagal: $msg"
        Store.upsert(ctx, s)
    }
}
