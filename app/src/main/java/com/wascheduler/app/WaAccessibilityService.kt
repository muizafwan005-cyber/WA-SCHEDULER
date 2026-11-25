package com.wascheduler.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: WaAccessibilityService? = null
        @Volatile var selectingRecipient = false
        @Volatile var recipientSawChatList = false

        // === PENGATURAN WHATSAPP GANDA ===
        // 0 = WhatsApp Asli (urutan pertama), 1 = WhatsApp Clone (urutan kedua)
        @Volatile var targetWaIndex: Int = 0

        const val TAG = "WA_SCHED"
        const val PREFS = "wa_scheduler"
        const val KEY_PENDING_NAME = "pending_recipient_name"
        const val KEY_PENDING_PACKAGE = "pending_recipient_package"
        const val KEY_PENDING_IDENTIFIER = "pending_recipient_identifier"
        const val KEY_TARGET_WA_INDEX = "target_wa_index"

        fun beginRecipientSelection() {
            recipientSawChatList = false
            selectingRecipient = true
        }
        fun cancelRecipientSelection() {
            selectingRecipient = false
            recipientSawChatList = false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastChooserClick = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        targetWaIndex = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_TARGET_WA_INDEX, targetWaIndex)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null; selectingRecipient = false; recipientSawChatList = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null; selectingRecipient = false; recipientSawChatList = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        Log.d(TAG, "pkg=${event.packageName} class=${event.className}")

        // PERBAIKAN 1: Tangani layar pemilih WhatsApp Xiaomi DULU
        if (handleChooser(event)) return

        if (selectingRecipient) { captureRecipient(); return }
        if (SendCoordinator.current != null) step()
    }

    // =====================================================
    // PEMILIH WHATSAPP ASLI / CLONE (CHOOSER XIAOMI)
    // =====================================================
    private fun handleChooser(event: AccessibilityEvent): Boolean {
        if (!selectingRecipient && SendCoordinator.current == null) return false
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val isChooser = cls.contains("Resolver", true) || cls.contains("Chooser", true) ||
            pkg == "com.android.intentresolver" || pkg == "com.google.android.intentresolver"
        if (!isChooser) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastChooserClick < 2000) return true
        lastChooserClick = now
        mainHandler.postDelayed({ clickChooserOption() }, 600)
        return true
    }

    private fun clickChooserOption() {
        val root = rootInActiveWindow ?: return
        targetWaIndex = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_TARGET_WA_INDEX, targetWaIndex)

        val expected = if (SendCoordinator.current?.pkg == "com.whatsapp.w4b")
            "WhatsApp Business" else "WhatsApp"

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        var candidates = all.filter {
            it.isVisibleToUser && labelOf(it) == expected &&
            it.className?.toString() != "android.widget.EditText"
        }
        if (candidates.isEmpty()) {
            candidates = all.filter {
                it.isVisibleToUser && labelOf(it).startsWith("WhatsApp") &&
                it.className?.toString() != "android.widget.EditText"
            }
        }
        val sorted = candidates
            .distinctBy { val r = Rect(); it.getBoundsInScreen(r); "${r.centerX()}_${r.centerY()}" }
            .sortedWith(compareBy({ topOf(it) }, { leftOf(it) }))

        Log.d(TAG, "Chooser: ketemu ${sorted.size}, pilih index $targetWaIndex ($expected)")
        if (sorted.size > targetWaIndex) click(sorted[targetWaIndex])
        else if (sorted.isNotEmpty()) click(sorted[0])
    }

    // =====================================================
    // RECIPIENT PICKER (tidak diubah dari kode aslimu)
    // =====================================================
    private fun captureRecipient() {
        if (!selectingRecipient) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        if (!hasChatComposer(root)) { recipientSawChatList = true; return }
        if (!recipientSawChatList) return
        val title = findConversationTitle(root) ?: return
        val name = title.trim()
        if (name.isBlank() || name.equals("WhatsApp", true) ||
            name.equals("WhatsApp Business", true)) return
        val identifier = extractPhoneNumberFromTitle(name)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_PENDING_NAME, name)
            .putString(KEY_PENDING_PACKAGE, pkg)
            .putString(KEY_PENDING_IDENTIFIER, identifier).apply()
        selectingRecipient = false; recipientSawChatList = false
        sendBroadcast(Intent("com.wascheduler.app.RECIPIENT_SELECTED").apply {
            setPackage(packageName)
            putExtra("recipient_name", name)
            putExtra("recipient_identifier", identifier)
            putExtra("whatsapp_package", pkg)
        })
    }

    private fun extractPhoneNumberFromTitle(title: String): String {
        val value = title.trim()
        if (!Regex("""^\+?[0-9][0-9\s().-]{6,}[0-9]$""").matches(value)) return ""
        val digits = value.filter { it.isDigit() }
        return if (digits.length in 8..15) digits else ""
    }

    private fun hasChatComposer(root: AccessibilityNodeInfo): Boolean {
        for (id in listOf("com.whatsapp:id/entry", "com.whatsapp:id/chat_input",
            "com.whatsapp.w4b:id/entry", "com.whatsapp.w4b:id/chat_input")) {
            if (!root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) return true
        }
        return false
    }

    private fun findConversationTitle(root: AccessibilityNodeInfo): String? {
        for (id in listOf("com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/conversation_title", "com.whatsapp:id/chat_title",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_title", "com.whatsapp.w4b:id/chat_title")) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) for (node in nodes) {
                val v = node.text?.toString()?.trim()
                if (!v.isNullOrBlank()) return v
            }
        }
        val titleNode = findNode(root) {
            val id = it.viewIdResourceName ?: ""
            id.endsWith(":id/title") && !it.text.isNullOrBlank()
        }
        return titleNode?.text?.toString()?.trim()
    }

    // =====================================================
    // PENGIRIMAN PESAN
    // =====================================================
    fun step() {
        val job = SendCoordinator.current ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != job.pkg) return
        val now = SystemClock.uptimeMillis()

        // PERBAIKAN 2: Kalau grup & belum dipilih, pilih grup DULU.
        // Kode lama mengecek tombol kirim dulu, jadi bisa nyangkut.
        if (job.isGroup && !job.chosen) { pickGroup(root, job, now); return }

        val send = findSend(root)
        if (send != null) {
            job.chosen = true
            if (now - job.lastClick > 1500) {
                if (click(send)) { job.clicks++; job.lastClick = now }
            }
            return
        }
        if (job.clicks > 0) {
            if (now - job.lastClick > 3000) SendCoordinator.finish(true, "")
            return
        }
    }

    private fun pickGroup(root: AccessibilityNodeInfo, job: SendCoordinator.Job, now: Long) {
        val name = job.target.trim()
        if (name.isBlank()) return

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        // PERBAIKAN 3: Cari SEMUA hasil yg terlihat, coba klik satu-satu dari atas
        val exact = all.filter {
            it.isVisibleToUser &&
            it.className?.toString() != "android.widget.EditText" &&
            it.text?.toString()?.trim().equals(name, ignoreCase = true)
        }.sortedWith(compareBy({ topOf(it) }, { leftOf(it) }))

        for (candidate in exact) {
            if (click(candidate)) { job.chosen = true; Log.d(TAG, "Grup dipilih: $name"); return }
        }

        if (job.typedAt > 0 && now - job.typedAt > 2000) {
            val partial = all.filter {
                it.isVisibleToUser &&
                it.className?.toString() != "android.widget.EditText" &&
                it.text?.toString()?.contains(name, ignoreCase = true) == true
            }.sortedWith(compareBy({ topOf(it) }, { leftOf(it) }))
            for (candidate in partial) {
                if (click(candidate)) { job.chosen = true; return }
            }
        }

        val edit = all.firstOrNull {
            it.isVisibleToUser && it.className?.toString() == "android.widget.EditText"
        }
        if (edit != null) {
            if (edit.text?.toString()?.trim() != name) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    job.typedAt = now
                }
            }
            return
        }

        val search = all.firstOrNull {
            val d = it.contentDescription?.toString()?.trim()?.lowercase()
            val id = it.viewIdResourceName ?: ""
            it.isVisibleToUser && (d == "search" || d == "cari" ||
                id.endsWith(":id/menuitem_search"))
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
                ((t == "send" || t == "kirim") &&
                    it.className?.toString()?.contains("Button") == true)
        }
    }

    // =====================================================
    // HELPER
    // =====================================================
    private fun labelOf(n: AccessibilityNodeInfo): String =
        n.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString()?.trim().orEmpty()

    private fun topOf(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.top
    }
    private fun leftOf(n: AccessibilityNodeInfo): Int {
        val r = Rect(); n.getBoundsInScreen(r); return r.left
    }

    private fun collectNodes(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collectNodes(n.getChild(i), out)
    }

    private fun findNode(n: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (match(n)) return n
        for (i in 0 until n.childCount) {
            val result = findNode(n.getChild(i), match)
            if (result != null) return result
        }
        return null
    }

    // PERBAIKAN 4: Kalau performAction gagal, pakai klik sentuhan (gesture)
    private fun click(n: AccessibilityNodeInfo): Boolean {
        if (n.className?.toString() == "android.widget.EditText") return false
        var current: AccessibilityNodeInfo? = n
        var clickableAncestor: AccessibilityNodeInfo? = null
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable && current.isEnabled) {
                clickableAncestor = current
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            current = current.parent; depth++
        }
        val target = clickableAncestor ?: n
        val rect = Rect(); target.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120)).build()
        val ok = dispatchGesture(gesture, null, null)
        Log.d(TAG, "Gesture click=$ok di ${rect.centerX()},${rect.centerY()}")
        return ok
    }
}
