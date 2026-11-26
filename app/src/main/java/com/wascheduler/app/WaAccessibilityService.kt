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
    private var lastDiagAt = 0L

    /** Log diagnostik, dibatasi 1x per 2 detik agar logcat tidak banjir. */
    private fun diag(msg: String) {
        val t = SystemClock.uptimeMillis()
        if (t - lastDiagAt > 2000) {
            lastDiagAt = t
            Log.d(TAG, msg)
        }
    }

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
        val root = findAppRoot(job.pkg)
        if (root == null) {
            diag("step: WhatsApp (${job.pkg}) belum di depan. activePkg=$activePkg target=${job.target}")
            return
        }
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
        diag(
            "Belum ada kotak pencarian untuk '$name'. Layar: " +
                all.filter { hasVisibleBounds(it) && topOf(it) < 700 }
                    .take(25)
                    .joinToString(" | ") {
                        val cls = it.className?.toString()?.substringAfterLast('.').orEmpty()
                        "$cls[${it.viewIdResourceName?.substringAfter(":id/").orEmpty()}]" +
                            "'${labelOf(it)}'" + if (it.isClickable) "*" else ""
                    }
        )
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
            return id.endsWith(":id/contactpicker_row_name") ||
                id.endsWith(":id/conversations_row_contact_name") ||
                id.endsWith(":id/row_contact_name") ||
                id.endsWith(":id/contactpicker_row_contact_name") ||
                id.endsWith(":id/name")
        }

        fun inVisibleScreen(n: AccessibilityNodeInfo): Boolean {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r.width() > 0 &&
                r.height() > 0 &&
                r.top >= minResultTop - 4 &&
                r.centerY() in 1 until screenH &&
                r.centerX() in 1 until screenW
        }

        fun isExact(n: AccessibilityNodeInfo): Boolean {
            val lbl = labelOf(n)
            return lbl.isNotBlank() && (
                lbl.equals(name, ignoreCase = true) ||
                    (nameDigits.length >= 8 && lbl.filter { it.isDigit() } == nameDigits)
                )
        }

        fun matchScore(n: AccessibilityNodeInfo): Int {
            val lbl = labelOf(n)
            if (lbl.isBlank()) return 0
            val exact = isExact(n)
            val partial = !exact && allowPartial && lbl.contains(name, ignoreCase = true)
            if (!exact && !partial) return 0

            val isRowId = isRowNameId(n)
            if (requireRowIdOnly && !isRowId) return 0

            var score = if (exact) 100 else 40
            if (n.isVisibleToUser) score += 50
            if (isRowId) score += 30
            if (findClickableRow(n, screenH) != null) score += 20
            return score
        }

        val scored = all.mapNotNull { node ->
            if (isEditableNode(node) || !inVisibleScreen(node)) return@mapNotNull null
            val score = matchScore(node)
            if (score <= 0) null else Pair(node, score)
        }
        // Nama persis menang atas nama sebagian ("Reloxon 2" jangan sampai memilih "Reloxon 22"),
        // dan baris daftar menang atas chip/label lain yang kebetulan bertuliskan nama sama.
        val exactOnly = if (scored.any { isExact(it.first) }) scored.filter { isExact(it.first) } else scored
        val rowOnly = if (exactOnly.any { isRowNameId(it.first) }) exactOnly.filter { isRowNameId(it.first) } else exactOnly

        return rowOnly
            .sortedWith(compareByDescending<Pair<AccessibilityNodeInfo, Int>> { it.second }
                .thenBy { topOf(it.first) })
            .map { it.first }
            .distinctBy {
                val r = Rect()
                it.getBoundsInScreen(r)
                "${r.top / 30}_${r.left / 30}"
            }
    }

    private fun findAppRoot(pkg: String): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == pkg) return active
        val winList = try { windows } catch (_: Exception) { null } ?: return null
        for (w in winList) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = w.root
                if (r != null && r.packageName?.toString() == pkg) return r
            }
        }
        return null
    }

    /** Cari foto profil (avatar) di dalam baris yang memuat nama kontak/grup. */
    private fun findAvatarNode(
        nameNode: AccessibilityNodeInfo,
        row: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val container = row ?: nameNode.parent ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(container, nodes)
        val nameRect = Rect()
        nameNode.getBoundsInScreen(nameRect)

        val byId = nodes.firstOrNull {
            val id = it.viewIdResourceName.orEmpty().lowercase()
            hasVisibleBounds(it) && !isEditableNode(it) && (
                id.contains("photo") || id.contains("avatar") ||
                    id.contains("picture") || id.contains("contact_icon")
                )
        }
        if (byId != null) return byId

        // Fallback: ImageView paling kiri yang posisinya di sebelah kiri nama
        return nodes.filter {
            val r = Rect()
            it.getBoundsInScreen(r)
            (it.className?.toString().orEmpty().contains("ImageView", true)) &&
                hasVisibleBounds(it) && r.right <= nameRect.left + 8
        }.minByOrNull { leftOf(it) }
    }

    /** Jika node avatar tidak terbaca: ketuk area di kiri nama (tempat foto profil berada). */
    private fun clickAvatarArea(
        nameNode: AccessibilityNodeInfo,
        row: AccessibilityNodeInfo?
    ): Boolean {
        val nameRect = Rect()
        nameNode.getBoundsInScreen(nameRect)
        val rowRect = Rect()
        (row ?: nameNode.parent)?.getBoundsInScreen(rowRect)
        if (rowRect.isEmpty || nameRect.left <= rowRect.left + 20) return false
        val x = (rowRect.left + nameRect.left) / 2
        val y = nameRect.centerY()
        return clickAt(x, y)
    }

    private fun findClickableRow(n: AccessibilityNodeInfo, screenH: Int): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 7) {
            if (cur.isClickable && cur.isEnabled) {
                val r = Rect()
                cur.getBoundsInScreen(r)
                if (r.height() in 30..(screenH * 2 / 5)) {
                    return cur
                }
            }
            cur = cur.parent
            depth++
        }
        return null
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
                val visible = list.firstOrNull { it.isVisibleToUser && hasVisibleBounds(it) }
                    ?: list.firstOrNull { hasVisibleBounds(it) }
                if (visible != null) return visible
            }
        }
        return findNode(root) {
            val d = it.contentDescription?.toString()?.trim()?.lowercase()
            val t = it.text?.toString()?.trim()?.lowercase()
            val cls = it.className?.toString().orEmpty()
            it.isVisibleToUser && hasVisibleBounds(it) && (
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

    private fun clickSmart(n: AccessibilityNodeInfo, preferGesture: Boolean): Boolean {
        if (isEditableNode(n)) return false
        return if (preferGesture) {
            clickGesture(n) || clickAction(n)
        } else {
            clickAction(n) || clickGesture(n)
        }
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
        return clickAt(rect.centerX(), rect.centerY())
    }

    private fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
