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
import android.view.accessibility.AccessibilityWindowInfo

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
    private var lastStepAt = 0L

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
        if (handleChooser(event)) return

        if (selectingRecipient) {
            captureRecipient()
            return
        }

        if (SendCoordinator.current != null) {
            // Event WhatsApp sangat sering (animasi daftar). Batasi agar main thread tidak penuh;
            // ticker 800ms di SendCoordinator tetap menjamin step() terus berjalan.
            val t = SystemClock.uptimeMillis()
            if (t - lastStepAt < 300) return
            lastStepAt = t
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
        // Diagnostik: tampilkan semua teks di dialog agar mudah dicek lewat logcat
        Log.d(
            TAG,
            "Chooser isi: " + all.filter { hasVisibleBounds(it) && labelOf(it).isNotBlank() }
                .take(30).joinToString(" | ") { "'${labelOf(it)}'@${leftOf(it)},${topOf(it)}" }
        )

        // Cara 1: kenali opsi Clone dari teksnya (Dual apps / Clone / dst) di node itu atau induknya.
        // Cara 2 (cadangan): urutan posisi di dialog.
        val cloneHints = listOf("dual", "clone", "kembar", "ganda", "second", "xspace", "space", "kloning")
        fun looksClone(n: AccessibilityNodeInfo): Boolean {
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 3) {
                val t = (cur.text?.toString().orEmpty() + " " +
                    cur.contentDescription?.toString().orEmpty()).lowercase()
                if (cloneHints.any { t.contains(it) }) return true
                cur = cur.parent
                depth++
            }
            return false
        }
        val wantClone = targetWaIndex == 1
        val cloneLike = sorted.filter { looksClone(it) }
        val chosen: AccessibilityNodeInfo? = when {
            cloneLike.isNotEmpty() && cloneLike.size < sorted.size ->
                if (wantClone) cloneLike[0] else sorted.firstOrNull { !looksClone(it) }
            sorted.size > targetWaIndex -> sorted[targetWaIndex]
            // Diminta Clone tetapi hanya ada satu opsi: JANGAN diam-diam memilih WhatsApp utama
            sorted.size == 1 && !wantClone -> sorted[0]
            else -> null
        }
        val note = if (wantClone) "Clone" else "Utama"
        if (chosen != null) {
            val clicked = clickAction(chosen) || clickGesture(chosen)
            SendCoordinator.current?.chooserNote =
                "dialog pilih WhatsApp $note sudah diklik (ok=$clicked) tetapi layar WhatsApp tidak terbuka"
        } else {
            SendCoordinator.current?.chooserNote =
                "opsi WhatsApp $note tidak ditemukan di dialog pilihan (ketemu ${sorted.size} opsi)"
        }
    }

    // =====================================================
    // TAMBAH PENERIMA DARI WHATSAPP
    // =====================================================
    private fun captureRecipient() {
        if (!selectingRecipient) return
        val root = findAppRoot("com.whatsapp") ?: findAppRoot("com.whatsapp.w4b") ?: return
        val pkg = root.packageName?.toString() ?: return

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

        val activeRoot = rootInActiveWindow
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activePkg == "com.miui.securitycore" ||
            activePkg == "com.android.intentresolver" ||
            activePkg == "com.google.android.intentresolver"
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastChooserClick >= 1800) {
                lastChooserClick = now
                clickChooserOption()
            }
            return
        }

        // Ambil jendela WhatsApp meskipun keyboard sedang aktif di depan
        val root = findAppRoot(job.pkg) ?: return
        val now = SystemClock.uptimeMillis()

        // 1. Jika tombol Kirim muncul, klik
        val send = findSend(root)
        if (send != null && hasVisibleBounds(send)) {
            job.chosen = true
            if (now - job.lastClick > 1500) {
                if (clickSmart(send, preferGesture = false)) {
                    job.clicks++
                    job.lastClick = now
                    Log.d(TAG, "Klik Kirim ke-${job.clicks} untuk ${job.target}")
                }
            }
            return
        }

        // 2. Setelah klik Kirim terakhir dan tombol Kirim sudah hilang > 4 detik -> selesai
        if (job.clicks > 0) {
            if (now - job.lastClick > job.settleMs) {
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

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all)

        // Cari kotak pencarian di bagian atas
        val searchEdit = all.firstOrNull { isSearchInput(it) }
        val minResultTop = if (searchEdit != null) {
            val r = Rect()
            searchEdit.getBoundsInScreen(r)
            r.bottom
        } else {
            0
        }

        if (searchEdit != null) job.chooserNote = null
        val typedText = cleanName(searchEdit?.text?.toString())
        val alreadyTyped = searchEdit != null && typedText.equals(name, ignoreCase = true)

        // Kumpulkan semua kandidat nama yang berada di bawah kotak pencarian & di dalam layar
        val candidates = findContactCandidates(
            all = all,
            name = name,
            minResultTop = minResultTop,
            screenW = screenW,
            screenH = screenH,
            allowPartial = alreadyTyped || (job.typedAt > 0 && now - job.typedAt > 1500),
            requireRowIdOnly = !(alreadyTyped || job.typedAt > 0)
        )

        if (candidates.isNotEmpty()) {
            // Setelah klik yang berhasil, beri WhatsApp waktu pindah layar (foto banyak = lebih lama).
            // Klik ulang terlalu cepat bisa MEMBATALKAN pilihan (toggle) atau mengetuk layar yang salah.
            val gap = if (job.lastPickOk) 3500L else 1000L
            if (now - job.lastPickClick > gap) {
                job.lastPickClick = now
                job.pickAttempts++

                // Selalu kandidat terbaik (nama persis, baris daftar). Jangan loncat ke nama lain.
                val targetNode = candidates[0]
                val rowNode = findClickableRow(targetNode, screenH)

                // Utamakan FOTO PROFIL di baris tersebut (lebih andal daripada teks nama),
                // baru fallback ke klik baris / nama.
                val avatar = findAvatarNode(targetNode, rowNode)
                val avatarOk = if (job.pickAttempts % 2 == 1) {
                    (avatar != null && clickGesture(avatar)) ||
                        (avatar != null && clickAction(avatar)) ||
                        clickAvatarArea(targetNode, rowNode)
                } else {
                    (avatar != null && clickAction(avatar)) ||
                        (avatar != null && clickGesture(avatar)) ||
                        clickAvatarArea(targetNode, rowNode)
                }
                val ok = avatarOk ||
                    (rowNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                    clickAction(targetNode) ||
                    clickGesture(targetNode)
                Log.d(TAG, "Klik foto profil: avatar=${avatar?.viewIdResourceName} avatarOk=$avatarOk")
                job.lastPickOk = ok

                val r = Rect()
                targetNode.getBoundsInScreen(r)
                Log.d(
                    TAG,
                    "Klik penerima '$name' attempt=${job.pickAttempts} " +
                        "cands=${candidates.size} id=${targetNode.viewIdResourceName} " +
                        "vis=${targetNode.isVisibleToUser} rect=$r ok=$ok"
                )
            }
            return
        }

        // Jika kotak pencarian sudah terbuka, isi teks tanpa langsung memunculkan keyboard
        if (searchEdit != null) {
            if (!alreadyTyped) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        name
                    )
                }
                var ok = searchEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!ok) {
                    searchEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    ok = searchEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                if (ok) {
                    job.typedAt = now
                    Log.d(TAG, "Ketik pencarian: '$name' di ${searchEdit.className}")
                }
            } else {
                // Log bantu jika nama sudah diketik tetapi belum ada kandidat yang cocok
                val visibleTexts = all.filter {
                    it.isVisibleToUser &&
                        !isEditableNode(it) &&
                        topOf(it) >= minResultTop &&
                        labelOf(it).isNotBlank()
                }.take(8).joinToString(" | ") { "${labelOf(it)}(${it.viewIdResourceName})" }
                Log.d(TAG, "Menunggu hasil '$name' di bawah y=$minResultTop. Terlihat: $visibleTexts")

                // Hasil pencarian tidak muncul (filter WA kadang tidak ter-refresh oleh SET_TEXT):
                // kosongkan lalu ketik ulang, maksimal 3 kali.
                if (job.typedAt > 0 && now - job.typedAt > 4000 && job.retypes < 3) {
                    job.retypes++
                    val clear = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            ""
                        )
                    }
                    searchEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear)
                    job.typedAt = 0L
                    Log.d(TAG, "Ketik ulang pencarian (retry ${job.retypes})")
                }
            }
            return
        }

        // Jika kotak pencarian belum terbuka, klik ikon Cari
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
                val ok = clickSmart(searchBtn, preferGesture = false)
                Log.d(TAG, "Klik tombol Cari id=${searchBtn.viewIdResourceName} ok=$ok")
            }
        }
    }

    private fun findContactCandidates(
        all: List<AccessibilityNodeInfo>,
        name: String,
        minResultTop: Int,
        screenW: Int,
        screenH: Int,
        allowPartial: Boolean,
        requireRowIdOnly: Boolean
    ): List<AccessibilityNodeInfo> {
        val nameDigits = name.filter { it.isDigit() }

        fun isRowNameId(n: AccessibilityNodeInfo): Boolean {
            val id = n.viewIdResourceName.orEmpty().lowercase()
            return id.endsWith
