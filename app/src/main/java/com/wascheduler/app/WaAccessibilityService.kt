
package com.wascheduler.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: WaAccessibilityService? = null

        @Volatile
        var selectingRecipient = false

        const val ACTION_RECIPIENT_SELECTED =
            "com.wascheduler.app.RECIPIENT_SELECTED"

        const val EXTRA_RECIPIENT_NAME = "recipient_name"
        const val EXTRA_WHATSAPP_PACKAGE = "whatsapp_package"

        /**
         * Dipanggil EditActivity sebelum membuka WhatsApp.
         */
        fun beginRecipientSelection() {
            selectingRecipient = true
        }

        /**
         * Membatalkan proses pemilihan penerima.
         */
        fun cancelRecipientSelection() {
            selectingRecipient = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        selectingRecipient = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        selectingRecipient = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Mode pemilihan penerima
        if (selectingRecipient) {
            captureRecipient()
            return
        }

        // Mode pengiriman terjadwal yang lama
        if (SendCoordinator.current != null) {
            step()
        }
    }

    /**
     * Membaca nama chat yang sedang dibuka.
     * Hanya mengambil nama ketika halaman percakapan terdeteksi.
     */
    private fun captureRecipient() {
        if (!selectingRecipient) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return

        if (pkg != "com.whatsapp" &&
            pkg != "com.whatsapp.w4b"
        ) return

        // Pastikan kita berada di halaman percakapan,
        // bukan halaman pencarian atau daftar chat.
        if (!hasChatComposer(root)) return

        val recipientName = findConversationTitle(root)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Hindari menyimpan judul umum aplikasi.
        if (recipientName.equals("WhatsApp", true)) return
        if (recipientName.equals("WhatsApp Business", true)) return

        // Simpan hasil agar EditActivity bisa mengambilnya
        // meskipun Activity sedang berada di background.
        val prefs = getSharedPreferences(
            "wa_scheduler",
            MODE_PRIVATE
        )

        prefs.edit()
            .putString("last_selected_recipient", recipientName)
            .putString("last_selected_whatsapp_package", pkg)
            .apply()

        selectingRecipient = false

        // Kirim hasil ke aplikasi WA Scheduler.
        val result = Intent(ACTION_RECIPIENT_SELECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RECIPIENT_NAME, recipientName)
            putExtra(EXTRA_WHATSAPP_PACKAGE, pkg)
        }

        sendBroadcast(result)
    }

    /**
     * Memastikan kolom penulisan pesan tersedia.
     */
    private fun hasChatComposer(
        root: AccessibilityNodeInfo
    ): Boolean {
        return findNode(root) {
            val id = it.viewIdResourceName ?: ""

            id.endsWith(":id/entry") ||
                id.endsWith(":id/chat_input") ||
                id.endsWith(":id/conversation_entry")
        } != null
    }

    /**
     * Mengambil nama dari toolbar percakapan.
     */
    private fun findConversationTitle(
        root: AccessibilityNodeInfo
    ): String? {

        val titleIds = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/conversation_title",
            "com.whatsapp:id/chat_title",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_title",
            "com.whatsapp.w4b:id/chat_title"
        )

        for (id in titleIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)

            if (!nodes.isNullOrEmpty()) {
                val value = nodes.firstNotNullOfOrNull {
                    it.text?.toString()?.trim()
                        ?.takeIf { name -> name.isNotBlank() }
                }

                if (value != null) return value
            }
        }

        // Cadangan: toolbar dengan ID title.
        val title = findNode(root) {
            val id = it.viewIdResourceName ?: ""

            id.endsWith(":id/title") &&
                it.text?.toString()?.isNotBlank() == true
        }

        return title?.text?.toString()
    }

    // =====================================================
    // PENGIRIMAN PESAN OTOMATIS (KODE LAMA DIPERTAHANKAN)
    // =====================================================

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

        // Tombol kirim sudah ditekan dan sekarang hilang.
        if (job.clicks > 0) {
            if (now - job.lastClick > 3000) {
                SendCoordinator.finish(true, "")
            }
            return
        }

        if (job.isGroup && !job.chosen) {
            pickGroup(root, job, now)
        }
    }

    private fun pickGroup(
        root: AccessibilityNodeInfo,
        job: SendCoordinator.Job,
        now: Long
    ) {
        val name = job.target.trim()

        var item = findNode(root) {
            it.className?.toString() != "android.widget.EditText" &&
                it.text?.toString()?.trim()
                    .equals(name, ignoreCase = true)
        }

        if (item == null &&
            job.typedAt > 0 &&
            now - job.typedAt > 2000
        ) {
            item = findNode(root) {
                it.className?.toString() != "android.widget.EditText" &&
                    it.text?.toString()
                        ?.contains(name, ignoreCase = true) == true
            }
        }

        if (item != null) {
            if (click(item)) job.chosen = true
            return
        }

        val edit = findNode(root) {
            it.className?.toString() == "android.widget.EditText"
        }

        if (edit != null) {
            if (edit.text?.toString() != name) {
                val args = Bundle()

                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    name
                )

                edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )

                job.typedAt = now
            }
            return
        }

        val search = findNode(root) {
            val d = it.contentDescription
                ?.toString()?.trim()?.lowercase()

            d == "search" || d == "cari"
        }

        if (search != null) click(search)
    }

    private fun findSend(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        for (id in listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )) {
            val list = root.findAccessibilityNodeInfosByViewId(id)

            if (!list.isNullOrEmpty()) return list[0]
        }

        return findNode(root) {
            val d = it.contentDescription
                ?.toString()?.trim()?.lowercase()

            val t = it.text
                ?.toString()?.trim()?.lowercase()

            d == "send" || d == "kirim" ||
                ((t == "send" || t == "kirim") &&
                    it.className?.toString()
                        ?.contains("Button") == true)
        }
    }

    private fun findNode(
        n: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {

        if (n == null) return null

        if (match(n)) return n

        for (i in 0 until n.childCount) {
            val result = findNode(n.getChild(i), match)

            if (result != null) return result
        }

        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = n

        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            }

            current = current.parent
        }

        return false
    }
}
