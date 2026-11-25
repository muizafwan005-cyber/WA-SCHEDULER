package com.wascheduler.app

import org.json.JSONArray
import org.json.JSONObject

data class Recipient(
    val name: String,
    val identifier: String = "",
    val pkg: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("identifier", identifier)
        put("pkg", pkg)
    }

    companion object {
        fun fromJson(o: JSONObject): Recipient = Recipient(
            name = o.optString("name").trim(),
            identifier = o.optString("identifier").trim(),
            pkg = o.optString("pkg").trim()
        )
    }
}

data class Schedule(
    val id: String,
    var name: String = "",
    var isGroup: Boolean = false,
    var target: String = "",
    var message: String = "",
    var attachments: MutableList<String> = mutableListOf(),
    var type: String = TYPE_ONCE,
    var onceAt: Long = 0L,
    var hour: Int = 8,
    var minute: Int = 0,
    var daysOfWeek: MutableList<Int> = mutableListOf(),
    var dayOfMonth: Int = 1,
    var enabled: Boolean = true,
    var nextRun: Long = 0L,
    var lastStatus: String = "",
    var whatsappTarget: String = WHATSAPP_MAIN,
    var recipientSelected: Boolean = false,
    var recipientName: String = "",
    var recipientIdentifier: String = "",
    var recipients: MutableList<Recipient> = mutableListOf()
) {
    /** Mengembalikan daftar penerima aktif (termasuk migrasi otomatis dari jadwal lama). */
    fun effectiveRecipients(): List<Recipient> {
        if (recipients.isNotEmpty()) return recipients
        val fallbackName = recipientName.ifBlank { target }.trim()
        if (fallbackName.isBlank()) return emptyList()
        val fallbackId = if (recipientIdentifier.isNotBlank()) {
            recipientIdentifier
        } else if (!isGroup && target.count { it.isDigit() } >= 8) {
            target.filter { it.isDigit() }
        } else {
            ""
        }
        return listOf(Recipient(name = fallbackName, identifier = fallbackId))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isGroup", isGroup)
        put("target", target)
        put("message", message)
        put("attachments", JSONArray(attachments))
        put("type", type)
        put("onceAt", onceAt)
        put("hour", hour)
        put("minute", minute)
        put("daysOfWeek", JSONArray(daysOfWeek))
        put("dayOfMonth", dayOfMonth)
        put("enabled", enabled)
        put("nextRun", nextRun)
        put("lastStatus", lastStatus)
        put("whatsappTarget", whatsappTarget)
        put("recipientSelected", recipientSelected)
        put("recipientName", recipientName)
        put("recipientIdentifier", recipientIdentifier)
        val rArr = JSONArray()
        recipients.forEach { rArr.put(it.toJson()) }
        put("recipients", rArr)
    }

    companion object {
        const val TYPE_ONCE = "once"
        const val TYPE_DAILY = "daily"
        const val TYPE_WEEKLY = "weekly"
        const val TYPE_MONTHLY = "monthly"
        const val WHATSAPP_MAIN = "main"
        const val WHATSAPP_CLONE = "clone"

        fun fromJson(o: JSONObject): Schedule {
            val att = mutableListOf<String>()
            val aj = o.optJSONArray("attachments")
            if (aj != null) {
                for (i in 0 until aj.length()) att.add(aj.getString(i))
            }
            val days = mutableListOf<Int>()
            val dj = o.optJSONArray("daysOfWeek")
            if (dj != null) {
                for (i in 0 until dj.length()) days.add(dj.getInt(i))
            }
            val recs = mutableListOf<Recipient>()
            val rj = o.optJSONArray("recipients")
            if (rj != null) {
                for (i in 0 until rj.length()) {
                    val r = Recipient.fromJson(rj.getJSONObject(i))
                    if (r.name.isNotBlank()) recs.add(r)
                }
            }
            return Schedule(
                id = o.getString("id"),
                name = o.optString("name"),
                isGroup = o.optBoolean("isGroup"),
                target = o.optString("target"),
                message = o.optString("message"),
                attachments = att,
                type = o.optString("type", TYPE_ONCE),
                onceAt = o.optLong("onceAt"),
                hour = o.optInt("hour", 8),
                minute = o.optInt("minute", 0),
                daysOfWeek = days,
                dayOfMonth = o.optInt("dayOfMonth", 1),
                enabled = o.optBoolean("enabled", true),
                nextRun = o.optLong("nextRun"),
                lastStatus = o.optString("lastStatus"),
                whatsappTarget = o.optString("whatsappTarget", WHATSAPP_MAIN).let {
                    if (it == WHATSAPP_CLONE) WHATSAPP_CLONE else WHATSAPP_MAIN
                },
                recipientSelected = o.optBoolean("recipientSelected", false),
                recipientName = o.optString("recipientName"),
                recipientIdentifier = o.optString("recipientIdentifier"),
                recipients = recs
            )
        }
    }
}
