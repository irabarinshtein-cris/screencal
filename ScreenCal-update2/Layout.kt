package com.screencal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Слово из OCR с координатами (пиксели картинки) и номером строки Tesseract. */
data class OcrWord(val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val line: Int)

/** Кусок строки — слова, стоящие рядом (колонки таблицы разделяются большим пробелом). */
data class Seg(val text: String, val l: Int, val t: Int, val r: Int, val b: Int) {
    val h: Int get() = b - t
}

data class Extracted(
    val events: List<ParsedEvent>,
    /** Текст после разметки — для показа пользователю. */
    val text: String,
    /** true — таблица по дням без дат на скрине: даты посчитаны от выбранного понедельника. */
    val datesGuessed: Boolean,
    /** Похоже на расписание пар (таблица по дням недели). */
    val isSchedule: Boolean = false,
    /** Номер учебной недели со скрина («№8»), если найден. */
    val weekNumber: Int? = null,
)

/**
 * Разметка скрина по координатам слов:
 *  • таблица-сетка (колонки = дни, ячейки = пары) — каждая ячейка разбирается отдельно;
 *  • лента/список (даты-заголовки, события под ними) — построчно сверху вниз.
 */
object Layout {

    /** Неделя по умолчанию: в выходные — следующая, в будни — текущая. */
    fun defaultWeekStart(today: LocalDate): LocalDate =
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY)
            today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun segments(words: List<OcrWord>, imgW: Int, imgH: Int): List<Seg> {
        val fix = ScheduleParser.isCyrillic(words.joinToString(" ") { it.text })
        val res = mutableListOf<Seg>()
        for ((_, ws0) in words.groupBy { it.line }) {
            val ws = ws0.sortedBy { it.l }
            val hs = ws.map { it.b - it.t }.sorted()
            val h = max(hs[hs.size / 2], 8)
            var cur = mutableListOf(ws[0])
            fun flush() {
                val text = cur.joinToString(" ") { it.text }.let { if (fix) ScheduleParser.fixHomoglyphs(it) else it }
                if (text.any { it.isLetterOrDigit() }) {
                    res += Seg(text, cur.minOf { it.l }, cur.minOf { it.t }, cur.maxOf { it.r }, cur.maxOf { it.b })
                }
            }
            for (w in ws.drop(1)) {
                if (w.l - cur.last().r > 2.5 * h) { flush(); cur = mutableListOf(w) } else cur += w
            }
            flush()
        }
        // строка состояния телефона (часы, батарея)
        val portrait = imgH > imgW * 1.5
        return res.filter { !(portrait && it.b < imgH * 0.035) }
    }

    fun extract(words: List<OcrWord>, imgW: Int, imgH: Int, today: LocalDate, weekStart: LocalDate): Extracted {
        if (words.isEmpty()) return Extracted(emptyList(), "", false)
        val segs = segments(words, imgW, imgH)
        val listText = segs.sortedWith(compareBy({ it.t }, { it.l })).joinToString("\n") { it.text }
        val weekNo = ScheduleParser.weekNumberIn(listText)
        val looksLikePairs = Regex("(расписани|нед\\.|лекци|семинар|практик)").containsMatchIn(listText.lowercase())

        // --- ищем колонки по левому краю строк со временем ---
        val tol = max((imgW * 0.015).roundToInt(), 8)
        val timeLefts = segs.filter { ScheduleParser.lineInfo(it.text, today).times.isNotEmpty() }.map { it.l }.sorted()
        val clusters = mutableListOf<MutableList<Int>>()
        for (x in timeLefts) {
            if (clusters.isEmpty() || x - clusters.last().last() > tol) clusters += mutableListOf(x) else clusters.last() += x
        }
        val anchors = clusters.filter { it.size >= 2 }.map { it.min() }
        if (anchors.size < 2 || timeLefts.size < 4) {
            return Extracted(ScheduleParser.parse(listText, today), listText, false, looksLikePairs, weekNo)
        }

        // шаг колонок (пустые дни без пар тоже учитываем: индекс = расстояние / шаг)
        val diffs = anchors.zipWithNext { a, b -> b - a }
        val minDiff = diffs.min()
        val pitch = diffs.filter { it < minDiff * 1.3 }.average()
        val a0 = anchors.first()

        val columns = sortedMapOf<Int, MutableList<Seg>>()
        for (s in segs) {
            if (s.l < a0 - tol || s.r - s.l > pitch * 1.2) continue          // подписи и заголовки на всю ширину
            val k = ((s.l - a0 + tol) / pitch).toInt()
            if (abs(s.l - (a0 + k * pitch)) > pitch * 0.6) continue
            columns.getOrPut(k) { mutableListOf() } += s
        }

        // --- даты колонок: явная дата в заголовке или день недели, иначе — по порядку колонок ---
        var base: LocalDate? = null
        val offsets = mutableMapOf<Int, Int>()
        for ((k, list) in columns) {
            for (s in list.sortedBy { it.t }) {
                val info = ScheduleParser.lineInfo(s.text, today)
                if (info.times.isNotEmpty()) break                           // заголовок выше первой пары
                val explicit = Regex("\\d").containsMatchIn(s.text) && info.date != null
                if (explicit && base == null) base = info.date!!.minusDays(k.toLong())
                ScheduleParser.weekdayIn(s.text)?.let { offsets[k] = it.value - 1 }
            }
        }
        val guessed = base == null
        val start = base ?: weekStart

        // --- ячейки ---
        val events = mutableListOf<ParsedEvent>()
        val textOut = StringBuilder()
        for ((k, list) in columns) {
            val sorted = list.sortedBy { it.t }
            val hs = sorted.map { it.h }.sorted()
            val medH = hs[hs.size / 2]
            val cells = mutableListOf(mutableListOf(sorted[0]))
            for (s in sorted.drop(1)) {
                if (s.t - cells.last().maxOf { it.b } > medH) cells += mutableListOf(s) else cells.last() += s
            }
            val offset = if (base == null) offsets[k] ?: k else k
            textOut.append("— колонка ${k + 1} —\n")
            for (cell in cells) {
                val lines = cell.map { it.text }
                textOut.append(lines.joinToString("\n")).append("\n\n")
                val date = start.plusDays(offset.toLong())
                ScheduleParser.parseCell(lines, date, today, if (guessed) offset else null)?.let { events += it }
            }
        }
        return Extracted(events.sortedWith(compareBy({ it.date }, { it.start })), textOut.toString().trim(), guessed, true, weekNo)
    }
}
