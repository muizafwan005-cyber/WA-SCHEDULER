package com.wascheduler.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SendCoordinator {

    private class QueueItem(
        val scheduleId: String,
        val recipient: Recipient,
        val batchKey: String
    )

    private class BatchProgress(
        val total: Int,
        var done: Int = 0,
        var success: Int = 0,
        var lastError: String = ""
    )

    class Job(
        val scheduleId: String,
        val batchKey: String,
        val isGroup: Boolean,
        val target: String,
        val pkg: String,
        val settleMs: Long = 4000L
    ) {
        var lastPickOk = false
        var retypes = 0
        var chosen = false
        var clicks = 0
        var lastClick = 0L
        var typedAt = 0L
        var lastPickClick = 0L
        var pickAttempts = 0
    }

    private const val TIMEOUT_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<QueueItem>()
    private val batches = mutableMapOf<String, BatchProgress>()
    private var appCtx: Context? = null

    @Volatile
    var current: Job? = null
        private set

    private val timeout = Runnable {
        val j = current
        val reason = when {
            j == null -> "Waktu habis"
            j.clicks > 0 -> "Waktu habis setelah menekan Kirim"
            j.isGroup && j.typedAt == 0L && j.pickAttempts == 0 ->
                "Waktu habis, kotak pencarian WhatsApp tidak ditemukan"
            j.isGroup && j.pickAttempts == 0 ->
                "Waktu habis, hasil pencarian '${j.target}' tidak muncul"
            j.isGroup -> "Waktu habis, '${j.target}' sudah diklik tetapi layar tidak lanjut ke tombol Kirim"
            else -> "Waktu habis, tombol kirim tidak ditemukan"
        }
        finish(false, reason)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (current == null) return
            WaAccessibilityService.instance?.step()
            handler.postDelayed(this, 800)
        }
    }

    fun enqueue(ctx: Context, id: String) {
        appCtx = ctx.applicationContext
        val s = Store.get(ctx, id) ?: return
        val list = s.effectiveRecipients()
        if (list.isEmpty()) {
            record(ctx, s, false, "Tidak ada penerima")
            return
        }
        val batchKey = "${id}_${System.currentTimeMillis()}"
        batches[batchKey] = BatchProgress(total = list.size)
        list.forEach { recipient ->
            queue.addLast(QueueItem(id, recipient, batchKey))
        }
        if (current == null) handler.post { startNext() }
    }

    private fun startNext() {
        if (current != null) return
        val ctx = appCtx ?: return
        val item = queue.removeFirstOrNull() ?: return
        val s = Store.get(ctx, item.scheduleId)
        if (s == null) {
            startNext()
            return
        }
        val pkg = WaUtil.installedPackage(ctx)
        if (pkg == null) {
            record(ctx, s, false, "WhatsApp tidak terpasang")
            clearBatch(item.batchKey)
            startNext()
            return
        }
        val service = WaAccessibilityService.instance
        if (service == null) {
            record(ctx, s, false, "Layanan aksesibilitas belum aktif")
            clearBatch(item.batchKey)
            startNext()
            return
        }

        // Terapkan pilihan WhatsApp Utama (0) atau Clone (1) dari jadwal ini
        val waIndex = if (s.whatsappTarget == Schedule.WHATSAPP_CLONE) 1 else 0
        WaAccessibilityService.targetWaIndex = waIndex
        ctx.getSharedPreferences(WaAccessibilityService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(WaAccessibilityService.KEY_TARGET_WA_INDEX, waIndex)
            .apply()

        // Jika penerima punya nomor HP (belum tersimpan di kontak), kirim langsung ke nomor;
        // selain itu (kontak tersimpan atau grup), cari berdasarkan nama di daftar kirim WA.
        val useDirectPhone = item.recipient.identifier.isNotBlank() && s.attachments.isEmpty()
        val isSearchMode = !useDirectPhone
        val jobTarget = if (useDirectPhone) item.recipient.identifier else item.recipient.name

        val singleSchedule = s.copy(
            isGroup = isSearchMode,
            target = jobTarget
        )

        current = Job(
            scheduleId = s.id,
            batchKey = item.batchKey,
            isGroup = isSearchMode,
            target = jobTarget,
            pkg = pkg,
            // Foto lebih banyak = WhatsApp butuh waktu lebih lama memproses sebelum tombol Kirim selesai
            settleMs = (4000L + 1500L * s.attachments.size).coerceAtMost(15000L)
        )

        WaUtil.wakeScreen(ctx)
        try {
            service.startActivity(WaUtil.buildIntent(ctx, singleSchedule, pkg))
        } catch (e: Exception) {
            finish(false, "Gagal membuka WhatsApp: ${e.message}")
            return
        }
        handler.postDelayed(timeout, TIMEOUT_MS)
        handler.postDelayed(ticker, 1500)
    }

    private fun clearBatch(batchKey: String) {
        batches.remove(batchKey)
        queue.removeAll { it.batchKey == batchKey }
    }

    fun finish(ok: Boolean, msg: String) {
        val job = current ?: return
        current = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(ticker)

        val ctx = appCtx
        val progress = batches[job.batchKey]
        if (progress != null) {
            progress.done++
            if (ok) progress.success++ else progress.lastError = "${job.target}: $msg"
        }

        if (ctx != null && (progress == null || progress.done >= progress.total)) {
            batches.remove(job.batchKey)
            Store.get(ctx, job.scheduleId)?.let { s ->
                val allOk = progress == null || progress.success == progress.total
                val summary = if (progress == null || progress.total <= 1) {
                    if (ok) "" else msg
                } else if (allOk) {
                    "(${progress.success}/${progress.total} penerima)"
                } else {
                    "${progress.success}/${progress.total} terkirim (${progress.lastError})"
                }
                record(ctx, s, allOk, summary)
            }
            val toastText = if (progress == null || progress.total <= 1) {
                if (ok) "Pesan terkirim ke ${job.target}" else "Gagal ke ${job.target}: $msg"
            } else {
                "Selesai: ${progress.success}/${progress.total} penerima terkirim"
            }
            Toast.makeText(ctx, toastText, Toast.LENGTH_LONG).show()
        }

        WaAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_HOME
        )
        handler.postDelayed({ startNext() }, 2500)
    }

    private fun record(ctx: Context, s: Schedule, ok: Boolean, msg: String) {
        val t = SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date())
        s.lastStatus = when {
            ok && msg.isNotBlank() -> "$t - Terkirim $msg"
            ok -> "$t - Terkirim"
            else -> "$t - Gagal: $msg"
        }
        Store.upsert(ctx, s)
    }
}
