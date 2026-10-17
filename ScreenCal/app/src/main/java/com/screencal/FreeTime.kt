package com.screencal

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Slot(val start: LocalTime, val end: LocalTime) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

object FreeTime {
    /**
     * Свободные окна по дням.
     * busy — занятые интервалы (локальное время), dayStart/dayEnd — рамки дня,
     * minMinutes — минимальная длина окна, чтобы оно считалось свободным (по умолчанию 60).
     */
    fun compute(
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
        from: LocalDate,
        to: LocalDate,
        dayStart: LocalTime,
        dayEnd: LocalTime,
        minMinutes: Int,
    ): List<Pair<LocalDate, List<Slot>>> {
        val result = mutableListOf<Pair<LocalDate, List<Slot>>>()
        var day = from
        while (!day.isAfter(to)) {
            val ws = day.atTime(dayStart)
            val we = if (dayEnd == LocalTime.MIDNIGHT || !dayEnd.isAfter(dayStart)) day.plusDays(1).atStartOfDay() else day.atTime(dayEnd)
            val clipped = busy
                .filter { it.second.isAfter(ws) && it.first.isBefore(we) }
                .map { maxOf(it.first, ws) to minOf(it.second, we) }
                .sortedBy { it.first }
            val slots = mutableListOf<Slot>()
            var cursor = ws
            for ((s, e) in clipped) {
                if (s.isAfter(cursor)) addSlot(slots, cursor, s, minMinutes)
                if (e.isAfter(cursor)) cursor = e
            }
            if (we.isAfter(cursor)) addSlot(slots, cursor, we, minMinutes)
            result += day to slots
            day = day.plusDays(1)
        }
        return result
    }

    private fun addSlot(list: MutableList<Slot>, s: LocalDateTime, e: LocalDateTime, min: Int) {
        if (Duration.between(s, e).toMinutes() < min) return
        val end = if (e.toLocalDate().isAfter(s.toLocalDate())) LocalTime.MAX.withNano(0).withSecond(0) else e.toLocalTime()
        list += Slot(s.toLocalTime(), end)
    }

    fun fmtDuration(min: Long): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }
}
