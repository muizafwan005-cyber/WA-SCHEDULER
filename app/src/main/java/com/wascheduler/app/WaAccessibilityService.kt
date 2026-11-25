
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

        @Volatile
        var recipientSawChatList = false

        const val PREFS = "wa_scheduler"
        const val KEY_PENDING_NAME = "pending_recipient_name"
        const val KEY_PENDING_PACKAGE = "pending_recipient_package"
        const val KEY_PENDING_IDENTIFIER = "pending_recipient_identifier"

        fun beginRecipientSelection() {
            recipientSawChatList = false
            selectingRecipient = true
        }

        fun cancelRecipientSelection() {
            selectingRecipient = false
            recipientSawChatList = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        selectingRecipient = false
        recipientSawChatList = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        selectingRecipient = false
        recipientSawChatList = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (selectingRecipient) {
            captureRecipient()
            return
        }

        if (SendCoordinator.current != null) {
            step()
        }
    }

    // =====================================================
    // RECIPIENT PICKER
    // =====================================================

    private fun captureRecipient() {
        if (!selectingRecipient) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return

        if (pkg != "com.whatsapp" &&
            pkg != "com.whatsapp.w4b"
        ) return

        // Tandai bahwa halaman selain percakapan terlihat.
        // Pengguna harus melewati daftar chat sebelum
        // percakapan dipilih.
        if (!hasChatComposer(root)) {
            recipientSawChatList = true
            return
        }

        // Jangan langsung memilih chat lama yang terbuka
        // ketika WhatsApp baru diluncurkan.
        if (!recipientSawChatList) return

        val title = findConversationTitle(root) ?: return
        val name = title.trim()

        if (name.isBlank()) return
        if (name.equals("WhatsApp", true)) return
        if (name.equals("WhatsApp Business", true)) return

        val identifier = extractPhoneNumberFromTitle(name)

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_NAME, name)
            .putString(KEY_PENDING_PACKAGE, pkg)
            .putString(KEY_PENDING_IDENTIFIER, identifier)
            .apply()

        selectingRecipient = false
        recipientSawChatList = false

        sendBroadcast(
            Intent("com.wascheduler.app.RECIPIENT_SELECTED").apply {
                setPackage(packageName)
                putExtra("recipient_name", name)
                putExtra("recipient_identifier", identifier)
                putExtra("whatsapp_package", pkg)
            }
        )
    }

    private fun extractPhoneNumberFromTitle(title: String): String {
        val value = title.trim()

        if (!Regex("""^\+?[0-9][0-9\s().-]{6,}[0-9]$""")
                .matches(value)
        ) {
            return ""
        }

        val digits = value.filter { it.isDigit() }

        return if (digits.length in 8..15) {
            digits
        } else {
            ""
        }
    }

    private fun hasChatComposer(
        root: AccessibilityNodeInfo
    ): Boolean {
        val composerIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/chat_input",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp.w4b:id/chat_input"
        )

        for (id in composerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }

        return false
    }

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
                for (node in nodes) {
                    val value = node.text?.toString()?.trim()
                    if (!value.isNullOrBlank()) return value
                }
            }
        }

        // Cadangan jika versi WhatsApp menggunakan ID berbeda.
        val titleNode = findNode(root) {
            val id = it.viewIdResourceName ?: ""

            id.endsWith(":id/title") &&
                !it.text.isNullOrBlank()
        }

        return titleNode?.text?.toString()?.trim()
    }

    // =====================================================
    // PENGIRIMAN PESAN LAMA
    // Bagian ini dipertahankan dari kode asli.
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

            val t = it.text?.toString()?.trim()?.lowercase()

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
