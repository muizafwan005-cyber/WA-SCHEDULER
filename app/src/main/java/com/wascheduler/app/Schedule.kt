
package com.wascheduler.app

import org.json.JSONArray
import org.json.JSONObject

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

    // Identitas penerima dari WhatsApp Recipient Picker
    var recipientSelected: Boolean = false,
    var recipientName: String = "",
    var recipientIdentifier: String = ""
) {

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

        // Simpan identitas penerima
        put("recipientSelected", recipientSelected)
        put("recipientName", recipientName)
        put("recipientIdentifier", recipientIdentifier)
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
                for (i in 0 until aj.length()) {
                    att.add(aj.getString(i))
                }
            }

            val days = mutableListOf<Int>()
            val dj = o.optJSONArray("daysOfWeek")

            if (dj != null) {
                for (i in 0 until dj.length()) {
                    days.add(dj.getInt(i))
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

                whatsappTarget = o.optString(
                    "whatsappTarget",
                    WHATSAPP_MAIN
                ).let {
                    if (it == WHATSAPP_CLONE) {
                        WHATSAPP_CLONE
                    } else {
                        WHATSAPP_MAIN
                    }
                },

                recipientSelected = o.optBoolean(
                    "recipientSelected",
                    false
                ),
                recipientName = o.optString("recipientName"),
                recipientIdentifier = o.optString("recipientIdentifier")
            )
        }
    }
}
