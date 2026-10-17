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
    val location: String = "",
    val note: String = "",
    /** Для таблиц-сеток: номер колонки (день от начала недели), если дата взята не из скрина. */
    val dayOffset: Int? = null,
)

data class LineInfo(val date: LocalDate?, val times: List<Pair<LocalTime, LocalTime?>>, val rest: String)

/**
 * Разбор текста расписания.
 * Понимает: 17.10, 17.10.2026, 2026-10-17, 17 октября, пн/понедельник, сегодня/завтра,
 * 10:00-11:30, 10.00–11.30, с 10:00 до 11:30, одиночное 10:00.
 */
object ScheduleParser {

    private const val T = "([01]?\\d|2[0-3])[:.]([0-5]\\d)"
    private val RANGE = Regex("(?<!\\d)(?:(?<!\\p{L})с\\s*)?$T\\s*(?:-|–|—|до|по)\\s*$T(?!\\d)")
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

    // шаблоны применяются к тексту в нижнем регистре
    private val TYPE = Regex("(лекци|практик|лаборатор|семинар|зач[её]т|экзамен|консультац|комисси|пересдач)")
    private val ROOM = Regex(
        "^(?:[а-яёa-z]{1,2}-?\\d{1,4}[а-я]?(?![\\d:.])|.*(?<!\\p{L})(?:ауд|каб|кабинет|аудитория)\\.?\\s*№?\\s*\\d|спортзал)"
    )
    private val PERSON = Regex("\\p{Lu}\\.\\s?\\p{Lu}\\.|^\\p{Lu}\\p{Ll}+\\s+\\p{Lu}\\p{Ll}+\\s+\\p{Lu}\\p{Ll}+(вич|вна|ична|чна|оглы|кызы)\\b")

    // ---------- список (лента событий с датами-заголовками) ----------

    fun parse(text: String, today: LocalDate): List<ParsedEvent> {
        class Ev(val date: LocalDate, val start: LocalTime, val end: LocalTime?, var title: String, val orphan: String?) {
            var location = ""
            val notes = mutableListOf<String>()
        }

        val fix = isCyrillic(text)
        val out = mutableListOf<Ev>()
        var ctxDate: LocalDate? = null
        var lastEvents: List<Ev> = emptyList()
        var pending: List<Ev> = emptyList()
        var extra = 0
        var continuation = false
        var orphan: String? = null

        for (raw in text.lines()) {
            val line = (if (fix) fixHomoglyphs(raw) else raw).trim()
            if (line.isEmpty()) continue
            val info = lineInfo(line, today)
            if (info.date != null) ctxDate = info.date

            if (info.times.isNotEmpty()) {
                val date = ctxDate ?: today
                val title = stripIcon(info.rest)
                val created = info.times.map { (s, e) -> Ev(date, s, e, title, orphan) }
                out += created
                orphan = null
                lastEvents = created
                pending = created.filter { it.title.isBlank() }
                extra = 2
                continuation = true
                continue
            }
            if (info.date != null) {
                lastEvents = emptyList(); pending = emptyList(); orphan = null
                continue
            }
            val r = info.rest
            if (!r.any { it.isLetterOrDigit() }) continue

            when {
                pending.isNotEmpty() -> { pending.forEach { it.title = stripIcon(r) }; pending = emptyList() }
                lastEvents.isNotEmpty() && continuation && r.first().isLowerCase() && !isRoom(r) ->
                    lastEvents.forEach { it.title = "${it.title} $r" }
                lastEvents.isNotEmpty() && extra > 0 -> {
                    continuation = false
                    extra--
                    lastEvents.forEach { if (isRoom(r) && it.location.isBlank()) it.location = r else it.notes += r }
                }
                else -> { orphan = r; lastEvents = emptyList() }
            }
        }

        return out.map {
            ParsedEvent(it.date, it.start, it.end, it.title.ifBlank { it.orphan ?: "Событие" }, it.location, it.notes.joinToString("\n"))
        }.distinctBy { Triple(it.date, it.start, it.title.lowercase()) }
    }

    // ---------- одна ячейка таблицы-сетки ----------

    /** Ячейка: «Предмет / Преподаватель / Тип занятия / Аудитория / 09:30 – 11:00». */
    fun parseCell(lines: List<String>, date: LocalDate, today: LocalDate, dayOffset: Int?): ParsedEvent? {
        val infos = lines.map { lineInfo(it, today) }
        // в таблице у пары всегда есть начало и конец; одиночное время — подпись/служебный текст
        val ti = infos.indexOfFirst { info -> info.times.any { it.second != null } && info.date == null }
        if (ti < 0) return null
        val (start, end) = infos[ti].times.first { it.second != null }

        val texts = lines.indices.mapNotNull { i -> if (i == ti) infos[i].rest else clean(lines[i]) }
            .map { stripIcon(it) }
            .filter { s -> s.count { it.isLetter() } >= 2 || (s.any { it.isLetter() } && s.any { it.isDigit() }) }

        val room = texts.firstOrNull { isRoom(it) } ?: ""
        val typeLine = texts.firstOrNull { it != room && isType(it) }
        val subject = texts.firstOrNull { it != room && it != typeLine && !isPerson(it) }
        val type = typeLine?.let { shortType(it) } ?: ""
        val notes = texts.filter { it != room && it != typeLine && it != subject }

        val title = listOf(subject ?: "", type).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "Занятие" }
        return ParsedEvent(date, start, end, title, room, notes.joinToString("\n"), dayOffset)
    }

    private fun shortType(s: String): String =
        s.substringBefore("(")
            .replace(Regex("\\s*[+«»*•·]\\s*"), " • ")
            .replace(Regex("(\\.\\.\\.|…)$"), "")
            .trim()

    fun isRoom(s: String): Boolean = ROOM.containsMatchIn(s.trim().lowercase())

    private fun isType(s: String): Boolean = TYPE.containsMatchIn(s.lowercase())

    private fun isPerson(s: String): Boolean = PERSON.containsMatchIn(s)

    // ---------- вопросы о свободном времени ----------

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

    /** День недели из заголовка («Понедельник», «пт»), если он есть. */
    fun weekdayIn(s: String): DayOfWeek? = WEEKDAY.find(s.lowercase())?.let { dow(it.value) }

    // ---------- исправление OCR ----------

    fun isCyrillic(text: String): Boolean {
        var cyr = 0
        var lat = 0
        for (c in text) {
            if (c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return cyr > lat * 2
    }

    private val HOMO = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О',
        'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У', 'U' to 'И', 'N' to 'Л',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у', 'u' to 'и',
    )

    /** Латинские «двойники» в русском тексте (A-245 → А-245, MuC3UBKC → МиСЗИВКС). */
    fun fixHomoglyphs(s: String): String = Regex("[A-Za-z0-9]+").replace(s) { m ->
        val w = m.value
        if (w.none { it in 'A'..'Z' || it in 'a'..'z' }) return@replace w
        if (w.any { (it in 'A'..'Z' || it in 'a'..'z') && it !in HOMO }) return@replace w
        buildString {
            w.forEachIndexed { i, c ->
                append(
                    when {
                        c == '3' && (w.getOrNull(i - 1)?.isLetter() == true || w.getOrNull(i + 1)?.isLetter() == true) -> 'З'
                        else -> HOMO[c] ?: c
                    }
                )
            }
        }
    }

    /** Убирает значок-иконку, прочитанный как один символ в начале строки («а УиАИС» → «УиАИС»). */
    fun stripIcon(s: String): String {
        val t = s.trim()
        val first = t.substringBefore(' ', t)
        return if (first.length == 1 && t.length > 2 && !first[0].isDigit()) clean(t.substring(1)) else t
    }

    // ---------- разбор одной строки ----------

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

    fun lineInfo(raw: String, today: LocalDate): LineInfo {
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
        (" $s ").replace(Regex("(?<=\\s)[-–—|•·*,;:=]+(?=\\s)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it in "-–—|:;,.•·*=" }

    // ---------- даты ----------

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (_: DateTimeException) { null }

    private fun year(s: String): Int = s.toInt().let { if (it < 100) 2000 + it else it }

    /** Без года — ближайший к сегодняшнему дню (прошлое не дальше 2 месяцев). */
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
