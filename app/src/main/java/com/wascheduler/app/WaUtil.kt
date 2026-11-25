package com.wascheduler.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLConnection

object WaUtil {

    fun installedPackage(c: Context): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try {
                c.packageManager.getPackageInfo(it, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

    /** 0812xxx -> 62812xxx, buang spasi, tanda + dan strip. */
    fun normalizeNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("0")) d = "62" + d.drop(1)
        return d
    }

    private fun mime(f: File): String =
        URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"

    private fun commonMime(files: List<File>): String {
        val majors = files.map { mime(it).substringBefore('/') }.toSet()
        return if (majors.size == 1) majors.first() + "/*" else "*/*"
    }

    /** Susun Intent yang membuka WhatsApp sesuai jenis jadwal. */
    fun buildIntent(c: Context, s: Schedule, pkg: String): Intent {
        val files = s.attachments.map { File(it) }.filter { it.exists() }
        val intent: Intent
        if (files.isEmpty()) {
            intent = if (!s.isGroup) {
                val url = "https://wa.me/${normalizeNumber(s.target)}?text=${Uri.encode(s.message)}"
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, s.message)
                }
            }
        } else {
            val uris = ArrayList<Uri>()
            for (f in files) {
                val u = FileProvider.getUriForFile(c, c.packageName + ".files", f)
                c.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uris.add(u)
            }
            intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime(files[0])
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = commonMime(files)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            // Wajib pasang ClipData agar semua URI terbaca stabil di Android 11+ & Xiaomi Dual Apps
            val clip = ClipData.newUri(c.contentResolver, "attachments", uris[0])
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            intent.clipData = clip
            if (s.message.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, s.message)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!s.isGroup) intent.putExtra("jid", "${normalizeNumber(s.target)}@s.whatsapp.net")
        }
        intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return intent
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "wascheduler:send"
        )
        wl.acquire(60_000L)
    }
}
