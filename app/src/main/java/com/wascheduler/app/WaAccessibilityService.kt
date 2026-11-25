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
        @Volatile var targetWaIndex: Int = 0 // 0 = Utama, 1 = Clone

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
    private var lastSearchBtnClick = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        targetWaIndex = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_TARGET_WA_INDEX, targetWaIndex)
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
        Log.d(TAG, "pkg=${event.packageName} class=${event.className}")

        if (handleChooser(event)) return

        if (selectingRecipient) {
            captureRecipient()
            return
        }

        if (SendCoordinator.current != null) {
            step()
        }
    }

    // =====================================================
    // PEMILIH WHATSAPP UTAMA / CLONE (XIAOMI XSPACE)
    // =====================================================
    private fun handleChooser(event: AccessibilityEvent): Boolean {
        if (!selectingRecipient && SendCoordinator.current == null) return false
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val isChooser = pkg == "com.miui.securitycore" ||
            pkg == "com.android.intentresolver" ||
            pkg == "com.google.android.intentresolver" ||
            cls.contains("XSpace", true) ||
            cls.contains("Resolver", true) ||
            cls.contains("Chooser", true)
        if (!isChooser) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastChooserClick < 1800) return true
        lastChooserClick = now
        mainHandler.postDelayed({ clickChooserOption() }, 500)
        return true
    }

    private fun clickChooserOption() {
        val root = rootInActiveWindow ?: return
        targetWaIndex = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_TARGET_WA_INDEX, targetWaIndex)

        val expected = if (SendCoordinator.current?.pkg == "com.whatsapp.w4b") {
            "WhatsApp Business"
        } else {
            "WhatsApp"
        }

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        var candidates = all.filter {
            hasVisibleBounds(it) &&
                labelOf(it).equals(expected, ignoreCase = true) &&
                !isEditableNode(it)
        }
        if (candidates.isEmpty()) {
            candidates = all.filter {
                hasVisibleBounds(it) &&
                    labelOf(it).startsWith("WhatsApp", ignoreCase = true) &&
                    !isEditableNode(it)
            }
        }

        val sorted = candidates
            .distinctBy {
                val r = Rect()
                it.getBoundsInScreen(r)
                "${r.centerX() / 40}_${r.centerY() / 40}"
            }
            .sortedWith(compareBy({ topOf(it) / 120 }, { leftOf(it) }))

        Log.d(TAG, "Chooser: ketemu ${sorted.size}, pilih index $targetWaIndex ($expected)")
        val chosen = when {
            sorted.size > targetWaIndex -> sorted[targetWaIndex]
            sorted.isNotEmpty() -> sorted[0]
            else -> null
        }
        if (chosen != null) {
            if (!clickAction(chosen)) clickGesture(chosen)
        }
    }

    // =====================================================
    // TAMBAH PENERIMA DARI WHATSAPP
    // =====================================================
    private fun captureRecipient() {
        if (!selectingRecipient) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        if (!hasChatComposer(root)) {
            recipientSawChatList = true
            return
        }
        if (!recipientSawChatList) return

        val title = findConversationTitle(root) ?: return
        val name = cleanName(title)
        if (name.isBlank() ||
            name.equals("WhatsApp", true) ||
            name.equals("WhatsApp Business", true)
        ) return

        val identifier = extractPhoneNumberFromTitle(name)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_PENDING_NAME, name)
            .putString(KEY_PENDING_PACKAGE, pkg)
            .putString(KEY_PENDING_IDENTIFIER, identifier)
            .apply()

        selectingRecipient = false
        recipientSawChatList = false

        sendBroadcast(Intent("com.wascheduler.app.RECIPIENT_SELECTED").apply {
            setPackage(packageName)
            putExtra("recipient_name", name)
            putExtra("recipient_identifier", identifier)
            putExtra("whatsapp_package", pkg)
        })

        mainHandler.postDelayed({
            try {
                val backIntent = Intent(this, EditActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                startActivity(backIntent)
            } catch (_: Exception) {}
        }, 300)
    }

    private fun extractPhoneNumberFromTitle(title: String): String {
        val value = cleanName(title)
        if (!Regex("""^\+?[0-9][0-9\s().-]{6,}[0-9]$""").matches(value)) return ""
        val digits = value.filter { it.isDigit() }
        return if (digits.length in 8..15) digits else ""
    }

    private fun hasChatComposer(root: AccessibilityNodeInfo): Boolean {
        for (id in listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/chat_input",
            "com.whatsapp.w4b:id/entry",
            "com.whatsapp.w4b:id/chat_input"
        )) {
            if (!root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) return true
        }
        return false
    }

    private fun findConversationTitle(root: AccessibilityNodeInfo): String? {
        for (id in listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/conversation_title",
            "com.whatsapp:id/chat_title",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_title",
            "com.whatsapp.w4b:id/chat_title"
        )) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val v = cleanName(node.text?.toString())
                    if (v.isNotBlank()) return v
                }
            }
        }
        val titleNode = findNode(root) {
            val id = it.viewIdResourceName ?: ""
            id.endsWith(":id/title") && !it.text.isNullOrBlank()
        }
        return cleanName(titleNode?.text?.toString()).takeIf { it.isNotBlank() }
    }

    // =====================================================
    // PENGIRIMAN PESAN & LAMPIRAN OTOMATIS
    // =====================================================
    fun step() {
        val job = SendCoordinator.current ?: return
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString().orEmpty()

        if (rootPkg == "com.miui.securitycore" ||
            rootPkg == "com.android.intentresolver" ||
            rootPkg == "com.google.android.intentresolver"
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastChooserClick >= 1800) {
                lastChooserClick = now
                clickChooserOption()
            }
            return
        }

        if (rootPkg != job.pkg) return
        val now = SystemClock.uptimeMillis()

        // 1. Jika tombol Kirim sudah muncul (baik tombol lanjut di "Kirim ke...",
        //    tombol Kirim di preview lampiran, dialog konfirmasi, atau ruang chat), klik.
        val send = findSend(root)
        if (send != null && hasVisibleBounds(send)) {
            job.chosen = true
            if (now - job.lastClick > 1500) {
                if (clickSmart(send, forceGesture = false)) {
                    job.clicks++
                    job.lastClick = now
                    Log.d(TAG, "Klik Kirim ke-${job.clicks} untuk ${job.target}")
                }
            }
            return
        }

        // 2. Setelah klik Kirim terakhir dan tombol Kirim sudah hilang > 4 detik -> selesai
        if (job.clicks > 0) {
            if (now - job.lastClick > 4000) {
                SendCoordinator.finish(true, "")
            }
            return
        }

        // 3. Pilih penerima di layar "Kirim ke..." / daftar kontak
        if (job.isGroup) {
            pickRecipientInList(root, job, now)
        }
    }

    private fun pickRecipientInList(
        root: AccessibilityNodeInfo,
        job: SendCoordinator.Job,
        now: Long
    ) {
        val name = cleanName(job.target)
        if (name.isBlank()) return

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        // Cari kotak pencarian di bagian atas (support EditText & AutoCompleteTextView)
        val searchEdit = all.firstOrNull { isSearchInput(it) }

        val minResultTop = if (searchEdit != null) {
            val r = Rect()
            searchEdit.getBoundsInScreen(r)
            r.bottom
        } else {
            0
        }

        val rowNameIds = setOf(
            "com.whatsapp:id/contactpicker_row_name",
            "com.whatsapp:id/conversations_row_contact_name",
            "com.whatsapp:id/row_contact_name",
            "com.whatsapp:id/name",
            "com.whatsapp.w4b:id/contactpicker_row_name",
            "com.whatsapp.w4b:id/conversations_row_contact_name",
            "com.whatsapp.w4b:id/row_contact_name",
            "com.whatsapp.w4b:id/name"
        )

        val typedText = cleanName(searchEdit?.text?.toString())
        val alreadyTyped = searchEdit != null && typedText.equals(name, ignoreCase = true)

        // Prioritas 1: Cek apakah nama target SUDAH terlihat di baris kontak (langsung klik)
        var match = all.filter {
            hasVisibleBounds(it) &&
                !isEditableNode(it) &&
                it.viewIdResourceName in rowNameIds &&
                topOf(it) >= minResultTop &&
                cleanName(it.text?.toString()).equals(name, ignoreCase = true)
        }.minByOrNull { topOf(it) }

        // Prioritas 2: Setelah mengetik, cek node teks mana pun di bawah kotak pencarian
        if (match == null && (alreadyTyped || job.typedAt > 0)) {
            match = all.filter {
                hasVisibleBounds(it) &&
                    !isEditableNode(it) &&
                    topOf(it) >= minResultTop &&
                    cleanName(it.text?.toString()).equals(name, ignoreCase = true)
            }.minByOrNull { topOf(it) }
        }

        // Prioritas 3 (setelah 1.8 detik): cocok sebagian (contains)
        if (match == null && (alreadyTyped || (job.typedAt > 0 && now - job.typedAt > 1800))) {
            match = all.filter {
                hasVisibleBounds(it) &&
                    !isEditableNode(it) &&
                    it.viewIdResourceName in rowNameIds &&
                    topOf(it) >= minResultTop &&
                    cleanName(it.text?.toString()).contains(name, ignoreCase = true)
            }.minByOrNull { topOf(it) }
                ?: all.filter {
                    hasVisibleBounds(it) &&
                        !isEditableNode(it) &&
                        topOf(it) >= minResultTop &&
                        cleanName(it.text?.toString()).contains(name, ignoreCase = true)
                }.minByOrNull { topOf(it) }
        }

        if (match != null) {
            if (now - job.lastPickClick > 1400) {
                job.lastPickClick = now
                job.pickAttempts++
                // Percobaan 1 pakai klik standar; percobaan 2+ paksa sentuhan layar tepat di nama
                val useGesture = job.pickAttempts >= 2
                val ok = clickSmart(match, forceGesture = useGesture)
                Log.d(
                    TAG,
                    "Klik penerima '$name' id=${match.viewIdResourceName} " +
                        "attempt=${job.pickAttempts} gesture=$useGesture ok=$ok"
                )
            }
            return
        }

        // Jika kotak pencarian sudah terbuka, ketik nama target
        if (searchEdit != null) {
            if (!alreadyTyped) {
                searchEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        name
                    )
                }
                if (searchEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    job.typedAt = now
                    Log.d(TAG, "Ketik pencarian: '$name' di ${searchEdit.className}")
                }
            } else {
                Log.d(TAG, "Sudah ketik '$name', menunggu hasil di bawah y=$minResultTop")
            }
            return
        }

        // Jika kotak pencarian belum terbuka, klik ikon Cari (kaca pembesar)
        if (now - lastSearchBtnClick > 1500) {
            val searchBtn = all.firstOrNull {
                val d = it.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                val id = it.viewIdResourceName.orEmpty().lowercase()
                hasVisibleBounds(it) && topOf(it) < 500 && (
                    d == "search" || d == "cari" ||
                        d.startsWith("search") || d.startsWith("cari") ||
                        id.endsWith(":id/menuitem_search") ||
                        id.endsWith(":id/search_btn") ||
                        id.endsWith(":id/search_icon") ||
                        id.endsWith(":id/search_button")
                )
            }
            if (searchBtn != null) {
                lastSearchBtnClick = now
                val ok = clickSmart(searchBtn, forceGesture = false)
                Log.d(TAG, "Klik tombol Cari id=${searchBtn.viewIdResourceName} ok=$ok")
            } else {
                Log.d(TAG, "Tombol Cari & nama '$name' belum ditemukan di layar ini")
            }
        }
    }

    private fun isEditableNode(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString().orEmpty()
        return n.isEditable ||
            cls.contains("EditText", true) ||
            cls.contains("AutoComplete", true)
    }

    private fun isSearchInput(n: AccessibilityNodeInfo): Boolean {
        if (!isEditableNode(n) || !hasVisibleBounds(n)) return false
        val id = n.viewIdResourceName.orEmpty().lowercase()
        if (id.endsWith(":id/entry") ||
            id.endsWith(":id/chat_input") ||
            id.contains("caption")
        ) return false
        return id.contains("search") || topOf(n) < 500
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) {
                val visible = list.firstOrNull { hasVisibleBounds(it) }
                if (visible != null) return visible
            }
        }
        return findNode(root) {
            val d = it.contentDescription?.toString()?.trim()?.lowercase()
            val t = it.text?.toString()?.trim()?.lowercase()
            val cls = it.className?.toString().orEmpty()
            hasVisibleBounds(it) && (
                d == "send" || d == "kirim" ||
                    ((t == "send" || t == "kirim") && cls.contains("Button", true))
            )
        }
    }

    // =====================================================
    // HELPER NODE & KLIK
    // =====================================================
    private fun cleanName(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]"), "")
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun labelOf(n: AccessibilityNodeInfo): String =
        cleanName(n.text?.toString()).takeIf { it.isNotBlank() }
            ?: cleanName(n.contentDescription?.toString())

    private fun hasVisibleBounds(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.width() > 0 && r.height() > 0
    }

    private fun topOf(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.top
    }

    private fun leftOf(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.left
    }

    private fun collectNodes(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) collectNodes(n.getChild(i), out)
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

    private fun clickSmart(n: AccessibilityNodeInfo, forceGesture: Boolean): Boolean {
        if (isEditableNode(n)) return false
        if (forceGesture) return clickGesture(n)
        return clickAction(n) || clickGesture(n)
    }

    private fun clickAction(n: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = n
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun clickGesture(n: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        n.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
