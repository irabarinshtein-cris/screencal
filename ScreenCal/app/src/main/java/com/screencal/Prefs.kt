package com.screencal

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "offline" — Tesseract на телефоне, "claude" — Claude API (точнее, нужен интернет и ключ). */
    var mode: String
        get() = sp.getString("mode", "offline") ?: "offline"
        set(v) = sp.edit().putString("mode", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var calendarId: Long
        get() = sp.getLong("calendarId", -1L)
        set(v) = sp.edit().putLong("calendarId", v).apply()

    var dayStart: String
        get() = sp.getString("dayStart", "08:00") ?: "08:00"
        set(v) = sp.edit().putString("dayStart", v).apply()

    var dayEnd: String
        get() = sp.getString("dayEnd", "22:00") ?: "22:00"
        set(v) = sp.edit().putString("dayEnd", v).apply()

    /** Минимальная длина свободного окна в минутах. */
    var minFree: Int
        get() = sp.getInt("minFree", 60)
        set(v) = sp.edit().putInt("minFree", v).apply()
}
