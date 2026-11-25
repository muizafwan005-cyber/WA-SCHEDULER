package com.wascheduler.app

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object Perms {
    fun accessibilityOn(c: Context): Boolean {
        val cn = ComponentName(c, WaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    fun exactAlarm(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun batteryOk(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(c.packageName)
}
