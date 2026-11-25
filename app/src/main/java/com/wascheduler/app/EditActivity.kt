
package com.wascheduler.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    private val dayLabels = listOf(
        "Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab"
    )

    private val dayChecks = mutableListOf<CheckBox>()
    private val cal: Calendar = Calendar.getInstance()
    private val attachments = mutableListOf<String>()

    private var existing: Schedule? = null

    private var recipientSelected = false
    private var recipientName = ""
    private var recipientIdentifier = ""

    private lateinit var etName: EditText
    private lateinit var etTarget: EditText
    private lateinit var etMessage: EditText
    private lateinit var etDom: EditText

    private lateinit var rbGroup: RadioButton
    private lateinit var spType: Spinner
    private lateinit var spWhatsApp: Spinner

    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var btnPickRecipient: Button

    private lateinit var llDays: LinearLayout
    private lateinit var tvFiles: TextView
    private lateinit var tvRecipient: TextView

    private val picker =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) copyFiles(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        etName = findViewById(R.id.etName)
        etTarget = findViewById(R.id.etTarget)
        etMessage = findViewById(R.id.etMessage)
        etDom = findViewById(R.id.etDom)

        rbGroup = findViewById(R.id.rbGroup)

        spType = findViewById(R.id.spType)
        spWhatsApp = findViewById(R.id.spWhatsApp)

        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        btnPickRecipient = findViewById(R.id.btnPickRecipient)

        llDays = findViewById(R.id.llDays)
        tvFiles = findViewById(R.id.tvFiles)
        tvRecipient = findViewById(R.id.tvRecipient)

        // Checkbox hari
        dayLabels.forEach { label ->
            val cb = CheckBox(this).apply {
                text = label
            }
            dayChecks.add(cb)
            llDays.addView(cb)
        }

        // Spinner pengulangan
        spType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            typeLabels
        )

        spType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: AdapterView<*>?,
                    v: View?,
                    pos: Int,
                    id: Long
                ) {
                    syncType()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // Spinner WhatsApp
        spWhatsApp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            whatsappLabels
        )

        findViewById<RadioGroup>(R.id.rgTarget)
            .setOnCheckedChangeListener { _, _ ->
                syncTargetHint()
            }

        // Tombol pilih penerima
        btnPickRecipient.setOnClickListener {
            pickRecipientFromWhatsApp()
        }

        // Bawaan: 10 menit dari sekarang
        cal.add(Calendar.MINUTE, 10)

        existing = intent.getStringExtra("id")?.let {
            Store.get(this, it)
        }

        existing?.let { fill(it) }

        syncTargetHint()
        syncType()
        refreshDateTime()
        refreshFiles()

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

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
        }
    }

    // =====================================================
    // PILIH PENERIMA WHATSAPP
    // =====================================================

    private fun pickRecipientFromWhatsApp() {

        // Tahap ini khusus WhatsApp Utama.
        if (
            whatsappTargets[spWhatsApp.selectedItemPosition] !=
            Schedule.WHATSAPP_MAIN
        ) {
            toast("Pemilihan WhatsApp Clone belum diaktifkan")
            return
        }

        if (WaAccessibilityService.instance == null) {
            toast(
                "Aktifkan Accessibility Service WA Scheduler terlebih dahulu"
            )
            return
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage("com.whatsapp")

        if (launchIntent == null) {
            toast("WhatsApp Utama tidak ditemukan")
            return
        }

        // Hapus hasil pemilihan sebelumnya.
        getSharedPreferences(
            WaAccessibilityService.PREFS,
            MODE_PRIVATE
        ).edit()
            .remove(WaAccessibilityService.KEY_PENDING_NAME)
            .remove(WaAccessibilityService.KEY_PENDING_PACKAGE)
            .apply()

        WaAccessibilityService.beginRecipientSelection()

        try {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            startActivity(launchIntent)

            toast("Buka chat atau grup yang ingin dipilih")
        } catch (e: Exception) {
            WaAccessibilityService.cancelRecipientSelection()
            toast("Gagal membuka WhatsApp: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()

        val prefs = getSharedPreferences(
            WaAccessibilityService.PREFS,
            MODE_PRIVATE
        )

        val name = prefs.getString(
            WaAccessibilityService.KEY_PENDING_NAME,
            null
        )

        if (!name.isNullOrBlank()) {
            recipientName = name
            recipientIdentifier = ""
            recipientSelected = true

            tvRecipient.text = "Penerima terpilih: $name"

            // Untuk grup, nama dapat digunakan oleh mekanisme
            // pencarian grup yang sudah ada.
            if (rbGroup.isChecked) {
                etTarget.setText(name)
            }

            prefs.edit()
                .remove(WaAccessibilityService.KEY_PENDING_NAME)
                .remove(WaAccessibilityService.KEY_PENDING_PACKAGE)
                .apply()
        }
    }

    // =====================================================
    // ISI FORM
    // =====================================================

    private fun fill(s: Schedule) {
        etName.setText(s.name)
        etTarget.setText(s.target)
        etMessage.setText(s.message)

        if (s.isGroup) {
            rbGroup.isChecked = true
        }

        recipientSelected = s.recipientSelected
        recipientName = s.recipientName
        recipientIdentifier = s.recipientIdentifier

        tvRecipient.text = if (recipientSelected) {
            "Penerima terpilih: $recipientName"
        } else {
            "Belum ada penerima dipilih"
        }

        val whatsappIndex = whatsappTargets.indexOf(s.whatsappTarget)
            .coerceAtLeast(0)

        spWhatsApp.setSelection(whatsappIndex)

        attachments.addAll(s.attachments)

        spType.setSelection(
            types.indexOf(s.type).coerceAtLeast(0)
        )

        etDom.setText(s.dayOfMonth.toString())

        s.daysOfWeek.forEach { d ->
            if (d in 1..7) {
                dayChecks[d - 1].isChecked = true
            }
        }

        if (s.type == Schedule.TYPE_ONCE && s.onceAt > 0) {
            cal.timeInMillis = s.onceAt
        } else {
            cal.set(Calendar.HOUR_OF_DAY, s.hour)
            cal.set(Calendar.MINUTE, s.minute)
        }
    }

    private fun syncTargetHint() {
        etTarget.hint = if (rbGroup.isChecked) {
            "Nama grup persis seperti di WhatsApp"
        } else {
            "Nomor tujuan, mis. 081234567890"
        }
    }

    private fun syncType() {
        val type = types[spType.selectedItemPosition]

        btnDate.visibility =
            if (type == Schedule.TYPE_ONCE) View.VISIBLE else View.GONE

        llDays.visibility =
            if (type == Schedule.TYPE_WEEKLY) View.VISIBLE else View.GONE

        etDom.visibility =
            if (type == Schedule.TYPE_MONTHLY) View.VISIBLE else View.GONE
    }

    // =====================================================
    // TANGGAL DAN WAKTU
    // =====================================================

    private fun refreshDateTime() {
        btnDate.text = "Tanggal: " +
            SimpleDateFormat(
                "dd MMM yyyy",
                Locale("id", "ID")
            ).format(cal.time)

        btnTime.text = "Jam: " +
            SimpleDateFormat(
                "HH:mm",
                Locale.US
            ).format(cal.time)
    }

    // =====================================================
    // LAMPIRAN
    // =====================================================

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

            if (i >= 0 && c.moveToFirst()) {
                name = c.getString(i) ?: "file"
            }
        }

        return name.replace(Regex("[^\\w.\\-]+"), "_")
    }

    private fun copyFiles(uris: List<Uri>) {
        Toast.makeText(
            this,
            "Menyalin lampiran…",
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            val dir = File(filesDir, "attachments").apply {
                mkdirs()
            }

            val added = mutableListOf<String>()

            for (u in uris) {
                try {
                    val out = File(
                        dir,
                        "${System.currentTimeMillis()}_${displayName(u)}"
                    )

                    contentResolver.openInputStream(u)?.use { input ->
                        out.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (out.exists() && out.length() > 0) {
                        added.add(out.absolutePath)
                    }
                } catch (e: Exception) {
                    // Lewati file yang gagal dibaca.
                }
            }

            runOnUiThread {
                attachments.addAll(added)
                refreshFiles()
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(
            this,
            msg,
            Toast.LENGTH_LONG
        ).show()
    }

    // =====================================================
    // SIMPAN JADWAL
    // =====================================================

    private fun save() {
        val target = etTarget.text.toString().trim()
        val message = etMessage.text.toString().trim()
        val type = types[spType.selectedItemPosition]

        if (target.isEmpty()) {
            toast(
                if (rbGroup.isChecked) {
                    "Pilih grup atau isi nama grup"
                } else {
                    "Isi nomor tujuan"
                }
            )
            return
        }

        // Nama kontak saja belum cukup untuk pengiriman otomatis.
        if (
            !rbGroup.isChecked &&
            target.count { it.isDigit() } < 8
        ) {
            toast(
                if (recipientSelected) {
                    "Nama kontak terdeteksi, tetapi nomor teleponnya belum tersedia. Masukkan nomor tujuan."
                } else {
                    "Nomor tidak valid"
                }
            )
            return
        }

        if (message.isEmpty() && attachments.isEmpty()) {
            toast("Isi pesan atau lampirkan file")
            return
        }

        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val days = dayChecks.indices
            .filter { dayChecks[it].isChecked }
            .map { it + 1 }

        if (type == Schedule.TYPE_WEEKLY && days.isEmpty()) {
            toast("Pilih minimal satu hari")
            return
        }

        val dom = etDom.text.toString().toIntOrNull() ?: 0

        if (type == Schedule.TYPE_MONTHLY && dom !in 1..31) {
            toast("Tanggal harus 1 sampai 31")
            return
        }

        if (
            type == Schedule.TYPE_ONCE &&
            cal.timeInMillis <= System.currentTimeMillis()
        ) {
            toast("Waktu kirim sudah lewat. Pilih waktu di masa depan.")
            return
        }

        val s = existing ?: Schedule(
            id = UUID.randomUUID().toString()
        )

        // Hapus lampiran lama yang sudah tidak dipakai.
        s.attachments
            .filter { it !in attachments }
            .forEach { File(it).delete() }

        s.name = etName.text.toString().trim().ifEmpty { target }

        s.isGroup = rbGroup.isChecked
        s.target = target
        s.message = message
        s.attachments = attachments.toMutableList()

        s.type = type
        s.onceAt = cal.timeInMillis
        s.hour = cal.get(Calendar.HOUR_OF_DAY)
        s.minute = cal.get(Calendar.MINUTE)

        s.daysOfWeek = days.toMutableList()
        s.dayOfMonth = if (dom in 1..31) dom else 1

        s.whatsappTarget = whatsappTargets[
            spWhatsApp.selectedItemPosition
        ]

        s.recipientSelected = recipientSelected
        s.recipientName = recipientName
        s.recipientIdentifier = recipientIdentifier

        s.lastStatus = ""

        Scheduler.activate(this, s)
        finish()
    }
}
