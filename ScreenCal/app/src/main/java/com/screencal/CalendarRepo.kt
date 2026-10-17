package com.screencal

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class CalInfo(val id: Long, val name: String, val account: String, val primary: Boolean)

/** Работа с календарём телефона (Google Календарь и любые другие, что есть в системе). */
object CalendarRepo {

    fun writableCalendars(ctx: Context): List<CalInfo> {
        val res = mutableListOf<CalInfo>()
        ctx.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.IS_PRIMARY),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                res += CalInfo(c.getLong(0), c.getString(1) ?: "Календарь", c.getString(2) ?: "", c.getInt(3) == 1)
            }
        }
        return res
    }

    /** Выбранный в настройках календарь, иначе основной, иначе создаём локальный. */
    fun resolveCalendar(ctx: Context, prefs: Prefs): Long {
        val cals = writableCalendars(ctx)
        cals.firstOrNull { it.id == prefs.calendarId }?.let { return it.id }
        val chosen = cals.firstOrNull { it.primary } ?: cals.firstOrNull()
        val id = chosen?.id ?: createLocalCalendar(ctx)
        prefs.calendarId = id
        return id
    }

    private fun createLocalCalendar(ctx: Context): Long {
        val account = "ScreenCal"
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "Мои планы")
            put(Calendars.CALENDAR_DISPLAY_NAME, "Мои планы")
            put(Calendars.CALENDAR_COLOR, Color.rgb(0x3F, 0x51, 0xB5))
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        val created = ctx.contentResolver.insert(uri, v) ?: error("Не удалось создать календарь")
        return ContentUris.parseId(created)
    }

    private fun millis(d: LocalDate, t: LocalTime): Long =
        d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Добавляет событие. rrule — правило повтора (null — разовое), tag — метка серии расписания.
     * false — такое уже есть (повторная отправка того же скрина).
     */
    fun insert(ctx: Context, calId: Long, ev: ParsedEvent, rrule: String? = null, tag: String? = null): Boolean {
        val start = millis(ev.date, ev.start)
        val endTime = ev.end ?: ev.start.plusHours(1)
        val end = if (endTime.isAfter(ev.start)) millis(ev.date, endTime) else millis(ev.date.plusDays(1), endTime)

        ctx.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} = ? AND ${Events.TITLE} = ? AND ${Events.DELETED} = 0",
            arrayOf(calId.toString(), start.toString(), ev.title), null,
        )?.use { if (it.count > 0) return false }

        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, ev.title)
            put(Events.DTSTART, start)
            if (rrule != null) {
                // у повторяющегося события вместо конца указывается длительность
                put(Events.DURATION, "P${(end - start) / 1000}S")
                put(Events.RRULE, rrule)
            } else {
                put(Events.DTEND, end)
            }
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            if (ev.location.isNotBlank()) put(Events.EVENT_LOCATION, ev.location)
            put(
                Events.DESCRIPTION,
                listOf(ev.note, "Добавлено из скриншота (СкринКалендарь)", tag ?: "").filter { it.isNotBlank() }.joinToString("\n"),
            )
        }
        ctx.contentResolver.insert(Events.CONTENT_URI, v) ?: error("Календарь отказал в записи")
        return true
    }

    /**
     * Новое расписание заменяет старое той же чётности недели: прежние серии пар обрываются
     * перед [from] (понедельник новой недели), а серии, начинающиеся с [from] и позже, удаляются.
     * Трогаются только события, созданные этим приложением в режиме «Расписание пар».
     */
    fun cutSchedule(ctx: Context, calId: Long, tag: String, from: LocalDate): Int {
        val zone = ZoneId.systemDefault()
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        var n = 0
        ctx.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.DTSTART, Events.RRULE),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?",
            arrayOf(calId.toString(), "%$tag%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
                val rule = c.getString(2)
                if (c.getLong(1) >= fromMs) {
                    ctx.contentResolver.delete(uri, null, null)
                    n++
                } else if (!rule.isNullOrBlank()) {
                    val v = ContentValues().apply { put(Events.RRULE, Recurrence.cutRule(rule, Instant.ofEpochMilli(fromMs - 1000))) }
                    ctx.contentResolver.update(uri, v, null, null)
                    n++
                }
            }
        }
        return n
    }

    /** Занятые интервалы из всех видимых календарей (события «свободен» и на весь день не учитываются). */
    fun busy(ctx: Context, from: LocalDate, to: LocalDate): List<Pair<LocalDateTime, LocalDateTime>> {
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, endMs)
        }.build()
        val res = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        ctx.contentResolver.query(
            uri,
            arrayOf(Instances.BEGIN, Instances.END),
            "${Instances.VISIBLE} = 1 AND ${Instances.ALL_DAY} = 0 AND ${Instances.AVAILABILITY} != ${Events.AVAILABILITY_FREE}",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                res += LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(0)), zone) to
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), zone)
            }
        }
        return res
    }
}
