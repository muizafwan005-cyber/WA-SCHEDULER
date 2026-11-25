package com.wascheduler.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val s = Store.get(context, id) ?: return
        if (!s.enabled) return

        // Jadwalkan pengiriman berikutnya lebih dulu agar tidak hilang jika pengiriman ini gagal
        if (s.type == Schedule.TYPE_ONCE) {
            s.enabled = false
            s.nextRun = 0L
            Store.upsert(context, s)
        } else {
            s.nextRun = Scheduler.computeNext(s, System.currentTimeMillis() + 1000)
            Store.upsert(context, s)
            Scheduler.setAlarm(context, s)
        }

        SendCoordinator.enqueue(context.applicationContext, id)
    }
}
