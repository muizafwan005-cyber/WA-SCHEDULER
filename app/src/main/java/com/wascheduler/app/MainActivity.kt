package com.wascheduler.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvPerm: TextView
    private val adapter = ScheduleAdapter()
    private var items = mutableListOf<Schedule>()
    private val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale("id", "ID"))
    private val dayNames = listOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listView)
        tvPerm = findViewById(R.id.tvPerm)
        listView.emptyView = findViewById(R.id.tvEmpty)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestNextPermission() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java))
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", items[pos].id))
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showActions(items[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this) // perbarui alarm (mis. setelah izin alarm tepat waktu diberikan)
        reload()
        updatePermText()
    }

    private fun reload() {
        items = Store.all(this).sortedWith(
            compareByDescending<Schedule> { it.enabled }.thenBy { if (it.nextRun > 0) it.nextRun else Long.MAX_VALUE }
        ).toMutableList()
        adapter.notifyDataSetChanged()
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun updatePermText() {
        val wa = WaUtil.installedPackage(this) != null
        tvPerm.text = listOf(
            "${mark(wa)} WhatsApp terpasang",
            "${mark(Perms.accessibilityOn(this))} Layanan aksesibilitas",
            "${mark(Perms.overlay(this))} Tampil di atas aplikasi lain",
            "${mark(Perms.exactAlarm(this))} Alarm tepat waktu",
            "${mark(Perms.batteryOk(this))} Bebas penghemat baterai"
        ).joinToString("\n")
    }

    private fun requestNextPermission() {
        val pkgUri = Uri.parse("package:$packageName")
        when {
            !Perms.accessibilityOn(this) -> {
                Toast.makeText(
                    this,
                    "Cari \"Penjadwal WA\" di daftar layanan lalu aktifkan",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !Perms.overlay(this) ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            !Perms.exactAlarm(this) -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
                }
            }
            !Perms.batteryOk(this) ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
            else ->
                Toast.makeText(this, "Semua izin sudah aktif", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(s: Schedule) {
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(arrayOf("Kirim sekarang (uji coba)", "Hapus jadwal")) { _, which ->
                if (which == 0) {
                    if (WaAccessibilityService.instance == null) {
                        Toast.makeText(this, "Aktifkan layanan aksesibilitas dulu", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Membuka WhatsApp…", Toast.LENGTH_SHORT).show()
                        SendCoordinator.enqueue(applicationContext, s.id)
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Hapus \"${s.name}\" beserta lampirannya?")
                        .setPositiveButton("Hapus") { _, _ ->
                            Scheduler.remove(this, s)
                            reload()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            .show()
    }

    private fun describe(s: Schedule): String {
        val t = "%02d:%02d".format(s.hour, s.minute)
        return when (s.type) {
            Schedule.TYPE_ONCE -> "Sekali kirim"
            Schedule.TYPE_DAILY -> "Setiap hari pukul $t"
            Schedule.TYPE_WEEKLY ->
                "Setiap " + s.daysOfWeek.sorted().joinToString(", ") { dayNames[it - 1] } + " pukul $t"
            else -> "Setiap tanggal ${s.dayOfMonth} pukul $t"
        }
    }

    inner class ScheduleAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_schedule, parent, false)
            val s = items[position]

            val who = (if (s.isGroup) "Grup: " else "Ke: ") + s.target
            val files = if (s.attachments.isNotEmpty()) " · ${s.attachments.size} lampiran" else ""
            v.findViewById<TextView>(R.id.tvTitle).text = s.name
            v.findViewById<TextView>(R.id.tvSub).text = "$who$files\n${describe(s)}"

            val status = when {
                s.enabled && s.nextRun > 0 -> "Berikutnya: " + fmt.format(Date(s.nextRun))
                s.lastStatus.isNotEmpty() -> s.lastStatus
                else -> "Nonaktif"
            }
            v.findViewById<TextView>(R.id.tvStatus).text =
                if (s.enabled && s.lastStatus.isNotEmpty()) "$status\nTerakhir: ${s.lastStatus}" else status

            val sw = v.findViewById<SwitchCompat>(R.id.sw)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = s.enabled
            sw.setOnClickListener {
                if (sw.isChecked) {
                    Scheduler.activate(this@MainActivity, s)
                    if (!s.enabled) {
                        Toast.makeText(
                            this@MainActivity,
                            "Waktu kirim sudah lewat. Ketuk jadwal untuk mengubahnya.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Scheduler.deactivate(this@MainActivity, s)
                }
                reload()
            }
            return v
        }
    }
}
