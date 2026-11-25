package com.wascheduler.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Menekan tombol di WhatsApp: memilih grup (jika perlu) dan menekan Kirim.
 * Hanya aktif saat ada pengiriman terjadwal berjalan.
 */
class WaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WaAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (SendCoordinator.current != null) step()
    }

    /** Satu langkah otomatisasi; dipanggil dari event dan dari ticker. */
    fun step() {
        val job = SendCoordinator.current ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != job.pkg) return
        val now = SystemClock.uptimeMillis()

        val send = findSend(root)
        if (send != null) {
            job.chosen = true
            if (now - job.lastClick > 1500) {
                if (click(send)) {
                    job.clicks++
                    job.lastClick = now
                }
            }
            return
        }

        // Tombol kirim sudah ditekan dan sekarang hilang: anggap selesai
        if (job.clicks > 0) {
            if (now - job.lastClick > 3000) SendCoordinator.finish(true, "")
            return
        }

        if (job.isGroup && !job.chosen) pickGroup(root, job, now)
    }

    private fun pickGroup(root: AccessibilityNodeInfo, job: SendCoordinator.Job, now: Long) {
        val name = job.target.trim()

        // 1) Item grup di daftar (bukan kolom ketik)
        var item = findNode(root) {
            it.className?.toString() != "android.widget.EditText" &&
                it.text?.toString()?.trim().equals(name, ignoreCase = true)
        }
        // Cadangan: setelah mengetik 2 detik, terima kecocokan sebagian
        if (item == null && job.typedAt > 0 && now - job.typedAt > 2000) {
            item = findNode(root) {
                it.className?.toString() != "android.widget.EditText" &&
                    it.text?.toString()?.contains(name, ignoreCase = true) == true
            }
        }
        if (item != null) {
            if (click(item)) job.chosen = true
            return
        }

        // 2) Ketik nama grup di kolom cari
        val edit = findNode(root) { it.className?.toString() == "android.widget.EditText" }
        if (edit != null) {
            if (edit.text?.toString() != name) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                job.typedAt = now
            }
            return
        }

        // 3) Buka kolom cari lewat ikon
        val search = findNode(root) {
            val d = it.contentDescription?.toString()?.trim()?.lowercase()
            d == "search" || d == "cari"
        }
        if (search != null) click(search)
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list[0]
        }
        return findNode(root) {
            val d = it.contentDescription?.toString()?.trim()?.lowercase()
            val t = it.text?.toString()?.trim()?.lowercase()
            d == "send" || d == "kirim" ||
                ((t == "send" || t == "kirim") && it.className?.toString()?.contains("Button") == true)
        }
    }

    private fun findNode(
        n: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (n == null) return null
        if (match(n)) return n
        for (i in 0 until n.childCount) {
            val r = findNode(n.getChild(i), match)
            if (r != null) return r
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
        }
        return false
    }
}
