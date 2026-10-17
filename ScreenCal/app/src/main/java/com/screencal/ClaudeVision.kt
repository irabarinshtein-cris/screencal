package com.screencal

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Распознавание скрина через Claude API (vision). Точнее офлайн-режима, нужен ключ и интернет. */
object ClaudeVision {

    fun recognize(bmp: Bitmap, apiKey: String, model: String, today: LocalDate): List<ParsedEvent> {
        val img = downscale(bmp, 1568)
        val baos = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val wd = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru"))
        val prompt = """
            На изображении расписание или план. Сегодня $today ($wd).
            Извлеки все события, у которых есть время.
            Верни ТОЛЬКО JSON-массив без пояснений:
            [{"date":"YYYY-MM-DD","start":"HH:MM","end":"HH:MM или null","title":"название события","location":"","note":""}]
            Если указан только день недели — возьми ближайшую такую дату, начиная с сегодня.
            Если год не указан — выбери ближайший к сегодняшней дате.
            Если это таблица по дням недели без дат — первая колонка это понедельник ${Layout.defaultWeekStart(today)}, следующие колонки — следующие дни.
            В "title" пиши предмет/событие и тип занятия, аудиторию/кабинет — в "location", преподавателя и прочее — в "note". Строку статуса телефона (часы, батарея) игнорируй.
        """.trimIndent()

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray()
                            .put(
                                JSONObject().put("type", "image").put(
                                    "source", JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "image/jpeg")
                                        .put("data", b64)
                                )
                            )
                            .put(JSONObject().put("type", "text").put("text", prompt))
                    )
                )
            )

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) error("Claude API $code: ${text.take(300)}")

        val content = JSONObject(text).getJSONArray("content")
        val answer = buildString {
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        val a = answer.indexOf('[')
        val b = answer.lastIndexOf(']')
        if (a < 0 || b < a) return emptyList()

        val arr = JSONArray(answer.substring(a, b + 1))
        val res = mutableListOf<ParsedEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: continue
            val start = ScheduleParser.parseTime(o.optString("start")) ?: continue
            val end = if (o.isNull("end")) null else ScheduleParser.parseTime(o.optString("end"))
            res += ParsedEvent(
                date, start, end, o.optString("title").ifBlank { "Событие" },
                o.optString("location").takeUnless { it == "null" } ?: "",
                o.optString("note").takeUnless { it == "null" } ?: "",
            )
        }
        return res
    }

    private fun downscale(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val k = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }
}
