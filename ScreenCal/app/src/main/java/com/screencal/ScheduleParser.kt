package com.screencal

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class ParsedEvent(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime?,
    val title: String,
)

/**
 * Разбирает текст расписания (после OCR) в список событий.
 * Понимает: 17.10, 17.10.2026, 2026-10-17, 17 октября, пн/понедельник, сегодня/завтра,
 * 10:00-11:30, 10.00–11.30, с 10:00 до 11:30, одиночное 10:00.
 * Дата из строки-заголовка применяется ко всем событиям ниже до следующей даты.
 */
object ScheduleParser {

    private val RANGE = Regex(
        "(?<!\\d)(?:(?<!\\p{L})с\\s*)?([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*(?:-|–|—|до|по)\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)"
    )
    private val ISO_DATE = Regex("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
    private val FULL_DATE = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4}|\\d{2})(?!\\d)")
    private const val MONTHS =
        "январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|ма[йя]\\p{L}*|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|" +
            "сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*|янв|фев|мар|апр|авг|сент|сен|окт|ноя|дек"
    private val TEXT_DATE = Regex("(?<!\\d)(\\d{1,2})\\s*($MONTHS)\\.?(?:\\s*(\\d{4}))?")
    private val TEXT_DAY_RANGE = Regex("(?<!\\d)(\\d{1,2})\\s*(?:-|–|—|по)\\s*(\\d{1,2})\\s*($MONTHS)")
    private val SHORT_DATE = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})(?!\\d)")
    private val SINGLE_TIME = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val WEEKDAY = Regex(
        "(?<!\\p{L})(понедельник|вторник|сред[аеуы]|четверг|пятниц[аеуы]|суббот[аеуы]|воскресень[ея]|" +
            "пн|вт|ср|чт|пт|сб|вс|mon|tue|wed|thu|fri|sat|sun)(?!\\p{L})"
    )
    private val RELATIVE = Regex("(?<!\\p{L})(сегодня|послезавтра|завтра)(?!\\p{L})")
    private val WEEK = Regex("(?<!\\p{L})(недел[юяеи]|7 дней)(?!\\p{L})")

    // ---------- публичное API ----------

    fun parse(text: String, today: LocalDate): List<ParsedEvent> {
        class Ev(val date: LocalDate, val start: LocalTime, val end: LocalTime?, var title: String, val orphan: String?)

        val out = mutableListOf<Ev>()
        var ctxDate: LocalDate? = null
        var lastEvents: List<Ev> = emptyList()
        var pending: List<Ev> = emptyList()   // события без названия, ждут следующую строку
        var extra = 0                          // сколько строк-пояснений можно дописать
        var orphan: String? = null             // текст без времени, пока никуда не пристроен

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val info = analyze(line, today)
            if (info.date != null) ctxDate = info.date

            if (info.times.isNotEmpty()) {
                val date = ctxDate ?: today
                val created = info.times.map { (s, e) -> Ev(date, s, e, info.rest, orphan) }
                out += created
                orphan = null
                lastEvents = created
                pending = created.filter { it.title.isBlank() }
                extra = 1
                continue
            }

            if (info.date != null) {           // строка-заголовок с датой
                lastEvents = emptyList(); pending = emptyList(); orphan = null
                continue
            }
            if (info.rest.isBlank()) continue

            when {
                pending.isNotEmpty() -> { pending.forEach { it.title = info.rest }; pending = emptyList() }
                lastEvents.isNotEmpty() && extra > 0 -> {
                    lastEvents.forEach { it.title = "${it.title} · ${info.rest}" }; extra--
                }
                else -> { orphan = info.rest; lastEvents = emptyList() }
            }
        }

        return out.map { ParsedEvent(it.date, it.start, it.end, it.title.ifBlank { it.orphan ?: "Событие" }) }
            .distinctBy { Triple(it.date, it.start, it.title.lowercase()) }
    }

    /** Все даты, упомянутые в вопросе («когда свободен 20–22 октября», «завтра», «в пятницу», «неделя»). */
    fun datesIn(text: String, today: LocalDate): List<LocalDate> {
        val l = Line(text)
        val res = mutableListOf<LocalDate>()
        for (m in l.take(TEXT_DAY_RANGE)) {
            val g = m.groupValues
            val month = month(g[3])
            val a = withYear(g[1].toInt(), month, null, today)
            val b = withYear(g[2].toInt(), month, null, today)
            if (a != null && b != null) { res += a; res += b }
        }
        for (m in l.take(ISO_DATE)) m.groupValues.let { g -> safeDate(g[1].toInt(), g[2].toInt(), g[3].toInt())?.let(res::add) }
        for (m in l.take(FULL_DATE)) m.groupValues.let { g -> safeDate(year(g[3]), g[2].toInt(), g[1].toInt())?.let(res::add) }
        for (m in l.take(TEXT_DATE)) m.groupValues.let { g -> withYear(g[1].toInt(), month(g[2]), g[3].toIntOrNull(), today)?.let(res::add) }
        for (m in l.take(SHORT_DATE)) m.groupValues.let { g -> withYear(g[1].toInt(), g[2].toInt(), null, today)?.let(res::add) }
        for (m in l.take(RELATIVE)) res += relative(m.value, today)
        for (m in l.take(WEEKDAY)) res += today.with(TemporalAdjusters.nextOrSame(dow(m.value)))
        if (l.take(WEEK).isNotEmpty()) { res += today; res += today.plusDays(6) }
        return res
    }

    fun parseTime(s: String): LocalTime? {
        val m = Regex("^\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*$").find(s) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    // ---------- разбор одной строки ----------

    private data class LineInfo(val date: LocalDate?, val times: List<Pair<LocalTime, LocalTime?>>, val rest: String)

    private class Line(orig: String) {
        val low: String = orig.lowercase()
        private val base: String = if (low.length == orig.length) orig else low
        private val used = BooleanArray(low.length)

        fun take(re: Regex): List<MatchResult> {
            val res = mutableListOf<MatchResult>()
            for (m in re.findAll(low)) {
                if (m.range.any { used[it] }) continue
                m.range.forEach { used[it] = true }
                res += m
            }
            return res
        }

        fun rest(): String = buildString { base.forEachIndexed { i, c -> append(if (used[i]) ' ' else c) } }
    }

    private fun analyze(raw: String, today: LocalDate): LineInfo {
        val l = Line(raw)
        val times = mutableListOf<Pair<Int, Pair<LocalTime, LocalTime?>>>()
        val singles = mutableListOf<Pair<Int, LocalTime>>()
        var date: LocalDate? = null

        for (m in l.take(RANGE)) {
            val g = m.groupValues
            times += m.range.first to (LocalTime.of(g[1].toInt(), g[2].toInt()) to LocalTime.of(g[3].toInt(), g[4].toInt()))
        }
        for (m in l.take(ISO_DATE)) m.groupValues.let { g -> date = date ?: safeDate(g[1].toInt(), g[2].toInt(), g[3].toInt()) }
        for (m in l.take(FULL_DATE)) m.groupValues.let { g -> date = date ?: safeDate(year(g[3]), g[2].toInt(), g[1].toInt()) }
        for (m in l.take(TEXT_DATE)) m.groupValues.let { g -> date = date ?: withYear(g[1].toInt(), month(g[2]), g[3].toIntOrNull(), today) }
        for (m in l.take(SINGLE_TIME)) singles += m.range.first to LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        for (m in l.take(SHORT_DATE)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val asDate = if (date == null) withYear(a, b, null, today) else null
            when {
                asDate != null -> date = asDate
                a in 0..23 && b in 0..59 -> singles += m.range.first to LocalTime.of(a, b)
            }
        }
        val rel = l.take(RELATIVE)
        val wd = l.take(WEEKDAY)
        if (date == null && rel.isNotEmpty()) date = relative(rel[0].value, today)
        if (date == null && wd.isNotEmpty()) date = today.with(TemporalAdjusters.nextOrSame(dow(wd[0].value)))

        singles.sortBy { it.first }
        if (times.isEmpty() && singles.size == 2 && singles[1].second.isAfter(singles[0].second)) {
            times += singles[0].first to (singles[0].second to singles[1].second)
        } else {
            singles.forEach { times += it.first to (it.second to null) }
        }
        return LineInfo(date, times.sortedBy { it.first }.map { it.second }, clean(l.rest()))
    }

    private fun clean(s: String): String =
        (" $s ").replace(Regex("(?<=\\s)[-–—|•·*,;:]+(?=\\s)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it in "-–—|:;,.•·*" }

    // ---------- даты ----------

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (_: DateTimeException) { null }

    private fun year(s: String): Int = s.toInt().let { if (it < 100) 2000 + it else it }

    /** Без года — берём ближайший к сегодняшнему дню (прошлое не дальше 2 месяцев). */
    private fun withYear(d: Int, m: Int, y: Int?, today: LocalDate): LocalDate? {
        if (m !in 1..12 || d !in 1..31) return null
        if (y != null) return safeDate(if (y < 100) 2000 + y else y, m, d)
        val c = safeDate(today.year, m, d) ?: return null
        return if (c.isBefore(today.minusMonths(2))) c.plusYears(1) else c
    }

    private fun month(w: String): Int = when (w.take(3)) {
        "янв" -> 1; "фев" -> 2; "мар" -> 3; "апр" -> 4; "май", "мая" -> 5; "июн" -> 6
        "июл" -> 7; "авг" -> 8; "сен" -> 9; "окт" -> 10; "ноя" -> 11; "дек" -> 12
        else -> 0
    }

    private fun relative(w: String, today: LocalDate): LocalDate = when (w) {
        "сегодня" -> today
        "завтра" -> today.plusDays(1)
        else -> today.plusDays(2)
    }

    private fun dow(w: String): DayOfWeek = when {
        w.startsWith("пон") || w == "пн" || w == "mon" -> DayOfWeek.MONDAY
        w.startsWith("вт") || w == "tue" -> DayOfWeek.TUESDAY
        w.startsWith("ср") || w == "wed" -> DayOfWeek.WEDNESDAY
        w.startsWith("чет") || w == "чт" || w == "thu" -> DayOfWeek.THURSDAY
        w.startsWith("пят") || w == "пт" || w == "fri" -> DayOfWeek.FRIDAY
        w.startsWith("суб") || w == "сб" || w == "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
