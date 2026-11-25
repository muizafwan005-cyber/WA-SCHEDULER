package com.wascheduler.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object Scheduler {

    /** Waktu kirim berikutnya setelah [from], atau 0 jika tidak ada. */
    fun computeNext(s: Schedule, from: Long): Long {
        if (s.type == Schedule.TYPE_ONCE) return if (s.onceAt > from) s.onceAt else 0L

        val cal = Calendar.getInstance()
        for (i in 0..400) {
            cal.timeInMillis = from
            cal.add(Calendar.DAY_OF_YEAR, i)
            cal.set(Calendar.HOUR_OF_DAY, s.hour)
            cal.set(Calendar.MINUTE, s.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= from) continue

            val ok = when (s.type) {
                Schedule.TYPE_DAILY -> true
                Schedule.TYPE_WEEKLY -> s.daysOfWeek.contains(cal.get(Calendar.DAY_OF_WEEK))
                Schedule.TYPE_MONTHLY -> cal.get(Calendar.DAY_OF_MONTH) == s.dayOfMonth
                else -> false
            }
            if (ok) return cal.timeInMillis
        }
        return 0L
    }

    private fun pending(c: Context, id: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            c, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun setAlarm(c: Context, s: Schedule) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, s.id)
        if (!s.enabled || s.nextRun <= 0L) {
            am.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.nextRun, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.nextRun, pi)
        }
    }

    /** Aktifkan jadwal: hitung waktu berikutnya lalu pasang alarm. */
    fun activate(c: Context, s: Schedule) {
        s.nextRun = computeNext(s, System.currentTimeMillis())
        s.enabled = s.nextRun > 0L
        Store.upsert(c, s)
        setAlarm(c, s)
    }

    fun deactivate(c: Context, s: Schedule) {
        s.enabled = false
        Store.upsert(c, s)
        setAlarm(c, s)
    }

    fun remove(c: Context, s: Schedule) {
        s.enabled = false
        setAlarm(c, s)
        s.attachments.forEach { java.io.File(it).delete() }
        Store.delete(c, s.id)
    }

    /** Dipanggil saat HP menyala ulang atau aplikasi dibuka. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        for (s in Store.all(c)) {
            if (!s.enabled) continue
            val n = computeNext(s, now)
            if (n == 0L) {
                s.enabled = false
                s.nextRun = 0L
                s.lastStatus = "Terlewat (HP mati atau aplikasi tidak aktif)"
            } else {
                s.nextRun = n
            }
            Store.upsert(c, s)
            setAlarm(c, s)
        }
    }
}
