package com.screencal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Варианты повтора события. rule — RRULE для календаря Android (RFC 5545). */
enum class Repeat(val label: String, val rule: String?) {
    NONE("Не повторять", null),
    DAILY("Ежедневно", "FREQ=DAILY"),
    WEEKLY("Еженедельно", "FREQ=WEEKLY;WKST=MO"),
    BIWEEKLY("Раз в 2 недели", "FREQ=WEEKLY;INTERVAL=2;WKST=MO"),
    MONTHLY("Ежемесячно", "FREQ=MONTHLY"),
    YEARLY("Ежегодно", "FREQ=YEARLY"),
}

/** Режим добавления: расписание пар (повтор раз в 2 недели, заменяет прежнее) или разовые события. */
enum class AddMode { SCHEDULE, ONE_TIME }

object Recurrence {
    private val UTC_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun utc(i: Instant): String = UTC_FMT.format(i)

    /** RRULE с датой окончания (включительно) или бессрочно. */
    fun rrule(repeat: Repeat, until: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val r = repeat.rule ?: return null
        return if (until == null) r else "$r;UNTIL=" + utc(until.atTime(23, 59, 59).atZone(zone).toInstant())
    }

    /** Обрезает существующую серию: повторы только до момента [end]. */
    fun cutRule(rrule: String, end: Instant): String =
        rrule.split(";").filter { it.isNotBlank() && !it.startsWith("UNTIL=") && !it.startsWith("COUNT=") }
            .joinToString(";") + ";UNTIL=" + utc(end)

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Чётность недели по календарю (0/1) — чтобы отличать «чётное» и «нечётное» расписание друг от друга. */
    fun parity(d: LocalDate): Int = Math.floorMod(Math.floorDiv(d.toEpochDay() + 3, 7L), 2L).toInt()

    fun scheduleTag(d: LocalDate): String = "#screencal-schedule:P${parity(d)}"

    /**
     * Пара из расписания: повтор раз в 2 недели с учётом пометок «(2–16 нед.)», «(на 8 нед.)».
     * weekNo — номер текущей учебной недели (неделя скрина), base — дата пары на этой неделе.
     * Возвращает (первая дата, повтор, последняя дата) или null, если пара уже закончилась.
     */
    fun planPair(base: LocalDate, weekNo: Int?, weekFrom: Int?, weekTo: Int?, oneOff: Boolean = false): Triple<LocalDate, Repeat, LocalDate?>? {
        if (weekNo == null || weekTo == null) return Triple(base, if (oneOff) Repeat.NONE else Repeat.BIWEEKLY, null)
        if (weekTo < weekNo) return null
        val from = maxOf(weekFrom ?: weekNo, weekNo)
        val first = base.plusWeeks((from - weekNo).toLong())
        val last = base.plusWeeks((weekTo - weekNo).toLong())
        return if (!last.isAfter(first)) Triple(first, Repeat.NONE, null) else Triple(first, Repeat.BIWEEKLY, last)
    }
}
