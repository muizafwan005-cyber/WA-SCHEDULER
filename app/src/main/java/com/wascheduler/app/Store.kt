package com.wascheduler.app

import android.content.Context
import org.json.JSONArray

object Store {
    private const val PREF = "wa_scheduler"
    private const val KEY = "list"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableList<Schedule> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Schedule>()
        for (i in 0 until arr.length()) out.add(Schedule.fromJson(arr.getJSONObject(i)))
        return out
    }

    @Synchronized
    private fun saveAll(c: Context, list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY, arr.toString()).commit()
    }

    fun get(c: Context, id: String): Schedule? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(c: Context, s: Schedule) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        saveAll(c, list)
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        saveAll(c, all(c).filter { it.id != id })
    }
}
