package com.wascheduler.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class EditActivity : AppCompatActivity() {

    private val types = listOf(
        Schedule.TYPE_ONCE,
        Schedule.TYPE_DAILY,
        Schedule.TYPE_WEEKLY,
        Schedule.TYPE_MONTHLY
    )
    private val typeLabels = listOf(
        "Sekali kirim",
        "Setiap hari",
        "Setiap minggu",
        "Setiap bulan"
    )
    private val whatsappTargets = listOf(
        Schedule.WHATSAPP_MAIN,
        Schedule.WHATSAPP_CLONE
    )
    private val whatsappLabels = listOf(
        "WhatsApp Utama",
        "WhatsApp Clone (XSpace)"
    )
    private val dayLabels = listOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
    private val dayChecks = mutableListOf<CheckBox>()
    private val cal: Calendar = Calendar.getInstance()
    private val attachments = mutableListOf<String>()
    private val recipients = mutableListOf<Recipient>()
    private var existing: Schedule? = null

    private lateinit var etName: EditText
    private lateinit var etMessage: EditText
    private lateinit var etDom: EditText
    private lateinit var spType: Spinner
    private lateinit var spWhatsApp: Spinner
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var btnPickRecipient: Button
    private lateinit var llDays: LinearLayout
    private lateinit var llRecipients: LinearLayout
    private lateinit var tvFiles: TextView
    private lateinit var tvRecipient: TextView

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) copyFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        etName = findViewById(R.id.etName)
        etMessage = findViewById(R.id.etMessage)
        etDom = findViewById(R.id.etDom)
        spType = findViewById(R.id.spType)
        spWhatsApp = findViewById(R.id.spWhatsApp)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        btnPickRecipient = findViewById(R.id.btnPickRecipient)
        llDays = findViewById(R.id.llDays)
        llRecipients = findViewById(R.id.llRecipients)
        tvFiles = findViewById(R.id.tvFiles)
        tvRecipient = findViewById(R.id.tvRecipient)

        dayLabels.forEach { label ->
            val cb = CheckBox(this).apply { text = label }
            dayChecks.add(cb)
            llDays.addView(cb)
        }

        spType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, typeLabels
        )
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                syncType()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spWhatsApp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, whatsappLabels
        )

        btnPickRecipient.setOnClickListener { pickRecipientFromWhatsApp() }

        cal.add(Calendar.MINUTE, 10)
        existing = intent.getStringExtra("id")?.let { Store.get(this, it) }
        existing?.let { fill(it) }

        syncType()
        refreshDateTime()
        refreshFiles()
        refreshRecipients()

        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    refreshDateTime()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        btnTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    cal.set(Calendar.HOUR_OF_DAY, h)
                    cal.set(Calendar.MINUTE, m)
                    refreshDateTime()
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        }

        findViewById<Button>(R.id.btnAttach).setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnClearFiles).setOnClickListener {
            attachments.clear()
            refreshFiles()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    // =====================================================
    // TAMBAH PENERIMA DARI WHATSAPP (UTAMA / CLONE)
    // =====================================================
    private fun pickRecipientFromWhatsApp() {
        if (WaAccessibilityService.instance == null) {
            toast("Aktifkan Accessibility Service WA Scheduler terlebih dahulu")
            return
        }

        val selectedTarget = whatsappTargets.getOrNull(spWhatsApp.selectedItemPosition)
            ?: Schedule.WHATSAPP_MAIN
        val waIndex = if (selectedTarget == Schedule.WHATSAPP_CLONE) 1 else 0

        // Simpan pilihan Utama (0) / Clone (1) agar chooser Xiaomi otomatis memilih yg benar
        WaAccessibilityService.targetWaIndex = waIndex
        getSharedPreferences(WaAccessibilityService.PREFS, MODE_PRIVATE).edit()
            .putInt(WaAccessibilityService.KEY_TARGET_WA_INDEX, waIndex)
            .remove(WaAccessibilityService.KEY_PENDING_NAME)
            .remove(WaAccessibilityService.KEY_PENDING_PACKAGE)
            .remove(WaAccessibilityService.KEY_PENDING_IDENTIFIER)
            .apply()

        // Untuk Clone, pakai intent tanpa Component eksplisit supaya dialog XSpace Xiaomi muncul
        val launchIntent = if (selectedTarget == Schedule.WHATSAPP_CLONE) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            packageManager.getLaunchIntentForPackage("com.whatsapp")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        if (launchIntent == null) {
            toast("WhatsApp tidak ditemukan")
            return
        }

        WaAccessibilityService.beginRecipientSelection()
        try {
            startActivity(launchIntent)
            toast(
                if (selectedTarget == Schedule.WHATSAPP_CLONE)
                    "Buka chat tujuan di WhatsApp Clone"
                else
                    "Buka daftar chat, lalu klik percakapan tujuan"
            )
        } catch (e: Exception) {
            // Fallback ke launcher standar jika intent tanpa component ditolak
            val fallback = packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(fallback)
            } else {
                WaAccessibilityService.cancelRecipientSelection()
                toast("Gagal membuka WhatsApp: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        readPendingRecipient()
    }

    private fun readPendingRecipient() {
        val prefs = getSharedPreferences(WaAccessibilityService.PREFS, MODE_PRIVATE)
        val name = prefs.getString(WaAccessibilityService.KEY_PENDING_NAME, null)?.trim()
        val identifier = prefs.getString(WaAccessibilityService.KEY_PENDING_IDENTIFIER, "")?.trim().orEmpty()
        val pkg = prefs.getString(WaAccessibilityService.KEY_PENDING_PACKAGE, "")?.trim().orEmpty()
        if (name.isNullOrBlank()) return

        // Hapus pending setelah dibaca
        prefs.edit()
            .remove(WaAccessibilityService.KEY_PENDING_NAME)
            .remove(WaAccessibilityService.KEY_PENDING_PACKAGE)
            .remove(WaAccessibilityService.KEY_PENDING_IDENTIFIER)
            .apply()

        val alreadyExists = recipients.any {
            it.name.equals(name, ignoreCase = true) ||
                (identifier.isNotBlank() && it.identifier == identifier)
        }
        if (alreadyExists) {
            toast("\"$name\" sudah ada di daftar penerima")
        } else {
            recipients.add(Recipient(name = name, identifier = identifier, pkg = pkg))
            refreshRecipients()
            toast("Ditambahkan: $name")
        }
    }

    private fun refreshRecipients() {
        llRecipients.removeAllViews()
        if (recipients.isEmpty()) {
            tvRecipient.text = "Belum ada penerima dipilih"
            btnPickRecipient.text = "+ Tambah Penerima dari WhatsApp"
            return
        }
        tvRecipient.text = "${recipients.size} penerima dipilih:"
        btnPickRecipient.text = "+ Tambah Penerima Lagi"

        recipients.forEachIndexed { index, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 4, 4, 4)
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                text = if (r.identifier.isNotBlank() && r.identifier != r.name) {
                    "${index + 1}. ${r.name} (${r.identifier})"
                } else {
                    "${index + 1}. ${r.name}"
                }
                textSize = 14f
            }
            val btnDel = Button(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Hapus"
                setOnClickListener {
                    recipients.removeAt(index)
                    refreshRecipients()
                }
            }
            row.addView(tv)
            row.addView(btnDel)
            llRecipients.addView(row)
        }
    }

    // =====================================================
    // ISI FORM (MODE EDIT)
    // =====================================================
    private fun fill(s: Schedule) {
        etName.setText(s.name)
        etMessage.setText(s.message)
        recipients.clear()
        recipients.addAll(s.effectiveRecipients())

        val whatsappIndex = whatsappTargets.indexOf(s.whatsappTarget).coerceAtLeast(0)
        spWhatsApp.setSelection(whatsappIndex)

        attachments.addAll(s.attachments)
        spType.setSelection(types.indexOf(s.type).coerceAtLeast(0))
        etDom.setText(s.dayOfMonth.toString())
        s.daysOfWeek.forEach { d ->
            if (d in 1..7) dayChecks[d - 1].isChecked = true
        }
        if (s.type == Schedule.TYPE_ONCE && s.onceAt > 0) {
            cal.timeInMillis = s.onceAt
        } else {
            cal.set(Calendar.HOUR_OF_DAY, s.hour)
            cal.set(Calendar.MINUTE, s.minute)
        }
    }

    private fun syncType() {
        val type = types[spType.selectedItemPosition]
        btnDate.visibility = if (type == Schedule.TYPE_ONCE) View.VISIBLE else View.GONE
        llDays.visibility = if (type == Schedule.TYPE_WEEKLY) View.VISIBLE else View.GONE
        etDom.visibility = if (type == Schedule.TYPE_MONTHLY) View.VISIBLE else View.GONE
    }

    private fun refreshDateTime() {
        btnDate.text = "Tanggal: " + SimpleDateFormat(
            "dd MMM yyyy", Locale("id", "ID")
        ).format(cal.time)
        btnTime.text = "Jam: " + SimpleDateFormat(
            "HH:mm", Locale.US
        ).format(cal.time)
    }

    private fun refreshFiles() {
        tvFiles.text = if (attachments.isEmpty()) {
            "Tanpa lampiran"
        } else {
            attachments.joinToString("\n") {
                "• " + File(it).name.substringAfter('_')
            }
        }
    }

    private fun displayName(u: Uri): String {
        var name = "file"
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: "file"
        }
        return name.replace(Regex("[^\\w.\\-]+"), "_")
    }

    private fun copyFiles(uris: List<Uri>) {
        Toast.makeText(this, "Menyalin lampiran…", Toast.LENGTH_SHORT).show()
        Thread {
            val dir = File(filesDir, "attachments").apply { mkdirs() }
            val added = mutableListOf<String>()
            for (u in uris) {
                try {
                    val out = File(dir, "${System.currentTimeMillis()}_${displayName(u)}")
                    contentResolver.openInputStream(u)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && out.length() > 0) added.add(out.absolutePath)
                } catch (_: Exception) {}
            }
            runOnUiThread {
                attachments.addAll(added)
                refreshFiles()
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // =====================================================
    // SIMPAN JADWAL
    // =====================================================
    private fun save() {
        val message = etMessage.text.toString().trim()
        val type = types[spType.selectedItemPosition]

        if (recipients.isEmpty()) {
            toast("Tambahkan minimal 1 penerima dari WhatsApp")
            return
        }
        if (message.isEmpty() && attachments.isEmpty()) {
            toast("Isi pesan atau lampirkan file")
            return
        }

        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val days = dayChecks.indices.filter { dayChecks[it].isChecked }.map { it + 1 }
        if (type == Schedule.TYPE_WEEKLY && days.isEmpty()) {
            toast("Pilih minimal satu hari")
            return
        }

        val dom = etDom.text.toString().toIntOrNull() ?: 0
        if (type == Schedule.TYPE_MONTHLY && dom !in 1..31) {
            toast("Tanggal harus 1 sampai 31")
            return
        }
        if (type == Schedule.TYPE_ONCE && cal.timeInMillis <= System.currentTimeMillis()) {
            toast("Waktu kirim sudah lewat. Pilih waktu di masa depan.")
            return
        }

        val s = existing ?: Schedule(id = UUID.randomUUID().toString())
        s.attachments.filter { it !in attachments }.forEach { File(it).delete() }

        val first = recipients.first()
        val summaryTarget = recipients.joinToString(", ") { it.name }

        s.name = etName.text.toString().trim().ifEmpty { summaryTarget }
        s.recipients = recipients.toMutableList()
        // Isi field lama untuk kompatibilitas tampilan di MainActivity
        s.target = summaryTarget
        s.isGroup = first.identifier.isBlank()
        s.recipientSelected = true
        s.recipientName = first.name
        s.recipientIdentifier = first.identifier

        s.message = message
        s.attachments = attachments.toMutableList()
        s.type = type
        s.onceAt = cal.timeInMillis
        s.hour = cal.get(Calendar.HOUR_OF_DAY)
        s.minute = cal.get(Calendar.MINUTE)
        s.daysOfWeek = days.toMutableList()
        s.dayOfMonth = if (dom in 1..31) dom else 1
        s.whatsappTarget = whatsappTargets[spWhatsApp.selectedItemPosition]
        s.lastStatus = ""

        Scheduler.activate(this, s)
        finish()
    }
}
