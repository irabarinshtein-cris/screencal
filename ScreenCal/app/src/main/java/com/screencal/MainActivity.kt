package com.screencal

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DATE_IN: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.uuuu")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val CAL_PERMS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

class MainActivity : ComponentActivity() {
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { AppTheme { App(sharedUri) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Приём скрина через «Поделиться» из галереи / после скриншота. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (uri != null) sharedUri.value = uri
    }
}

// ---------------- общее ----------------

data class Draft(
    val id: Long,
    val title: String,
    val date: String,
    val start: String,
    val end: String,
    val checked: Boolean = true,
)

private var idCounter = 0L

private fun ParsedEvent.toDraft() = Draft(
    id = ++idCounter,
    title = title,
    date = date.format(DATE_FMT),
    start = start.format(TIME_FMT),
    end = end?.format(TIME_FMT) ?: "",
    checked = title.any { it.isLetter() },
)

private fun Draft.toEvent(): ParsedEvent? {
    val d = runCatching { LocalDate.parse(date.trim(), DATE_IN) }.getOrNull() ?: return null
    val s = ScheduleParser.parseTime(start) ?: return null
    val e = if (end.isBlank()) null else ScheduleParser.parseTime(end) ?: return null
    if (title.isBlank()) return null
    return ParsedEvent(d, s, e, title.trim())
}

private fun hasPerms(ctx: Context, perms: Array<String>) =
    perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: error("Не удалось открыть изображение")

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(sharedUri: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val drafts = remember { mutableStateListOf<Draft>() }

    LaunchedEffect(sharedUri.value) { if (sharedUri.value != null) tab = 0 }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Add, null) }, label = { Text("Скрин") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("Свободно") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ScreenTab(prefs, drafts, sharedUri)
                1 -> FreeTab(prefs)
                else -> SettingsTab(prefs)
            }
        }
    }
}

// ---------------- вкладка «Скрин» ----------------

@Composable
fun ScreenTab(prefs: Prefs, drafts: SnapshotStateList<Draft>, sharedUri: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Отправьте скрин расписания: кнопкой ниже или через «Поделиться» из галереи.") }
    var rawText by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    fun process(uri: Uri) {
        busy = true
        status = "Распознаю…"
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }
                preview = bmp
                val today = LocalDate.now()
                val useClaude = prefs.mode == "claude" && prefs.apiKey.isNotBlank()
                val (text, events) = withContext(Dispatchers.Default) {
                    if (useClaude) {
                        "" to ClaudeVision.recognize(bmp, prefs.apiKey, prefs.model, today)
                    } else {
                        val t = Ocr.recognize(ctx, bmp)
                        t to ScheduleParser.parse(t, today)
                    }
                }
                rawText = text
                drafts.clear()
                drafts.addAll(events.map { it.toDraft() })
                status = if (events.isEmpty()) "События не найдены. Проверьте распознанный текст или добавьте вручную."
                else "Найдено: ${events.size}. Проверьте и нажмите «Добавить в календарь»."
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val list = drafts.toList()
        busy = true
        scope.launch {
            try {
                val (added, dup, bad) = withContext(Dispatchers.IO) {
                    val calId = CalendarRepo.resolveCalendar(ctx, prefs)
                    var a = 0
                    var d = 0
                    val b = mutableListOf<Int>()
                    list.forEachIndexed { i, dr ->
                        if (!dr.checked) return@forEachIndexed
                        val ev = dr.toEvent()
                        if (ev == null) b += i + 1
                        else if (CalendarRepo.insert(ctx, calId, ev)) a++ else d++
                    }
                    Triple(a, d, b)
                }
                status = buildString {
                    append("Добавлено в календарь: $added.")
                    if (dup > 0) append(" Уже были: $dup.")
                    if (bad.isNotEmpty()) append(" Исправьте дату/время в карточках №${bad.joinToString()}.")
                }
                if (bad.isEmpty()) drafts.clear()
            } catch (e: Exception) {
                status = "Ошибка записи: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(uri)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) save() else status = "Без доступа к календарю сохранить нельзя."
    }

    LaunchedEffect(sharedUri.value) {
        sharedUri.value?.let { process(it); sharedUri.value = null }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Text("Выбрать скрин") }
                OutlinedButton(
                    onClick = {
                        drafts += Draft(++idCounter, "", LocalDate.now().format(DATE_FMT), "", "")
                    },
                    enabled = !busy,
                ) { Text("Вручную") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
        preview?.let { bmp ->
            item {
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = "Скрин",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(16.dp),
                )
            }
        }
        items(drafts, key = { it.id }) { d ->
            DraftCard(
                d,
                onChange = { nd -> val i = drafts.indexOfFirst { it.id == d.id }; if (i >= 0) drafts[i] = nd },
                onDelete = { drafts.removeAll { it.id == d.id } },
            )
        }
        if (drafts.isNotEmpty()) {
            item {
                Button(
                    onClick = { if (hasPerms(ctx, CAL_PERMS)) save() else permLauncher.launch(CAL_PERMS) },
                    enabled = !busy && drafts.any { it.checked },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("Добавить в календарь (${drafts.count { it.checked }})") }
            }
        }
        if (rawText.isNotBlank()) {
            item {
                TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showRaw) "Скрыть распознанный текст" else "Показать распознанный текст")
                }
                if (showRaw) {
                    Text(
                        rawText, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun weekdayLabel(date: String): String =
    runCatching { LocalDate.parse(date.trim(), DATE_IN) }.getOrNull()
        ?.dayOfWeek?.getDisplayName(TextStyle.FULL, RU)?.replaceFirstChar { it.uppercase() }
        ?: "Дата не распознана"

@Composable
fun DraftCard(d: Draft, onChange: (Draft) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = d.checked, onCheckedChange = { onChange(d.copy(checked = it)) })
                Text(
                    weekdayLabel(d.date), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    d.title, { onChange(d.copy(title = it)) },
                    label = { Text("Событие") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        d.date, { onChange(d.copy(date = it)) }, label = { Text("Дата") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.5f),
                    )
                    OutlinedTextField(
                        d.start, { onChange(d.copy(start = it)) }, label = { Text("С") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        d.end, { onChange(d.copy(end = it)) }, label = { Text("До") }, singleLine = true,
                        placeholder = { Text("+1 ч") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------- вкладка «Свободно» ----------------

@Composable
fun FreeTab(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(LocalDate.now()) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var result by remember { mutableStateOf<List<Pair<LocalDate, List<Slot>>>?>(null) }
    var status by remember { mutableStateOf("") }

    fun compute() {
        if (to.isBefore(from)) { val t = from; from = to; to = t }
        if (to.isAfter(from.plusDays(31))) to = from.plusDays(31)
        val start = ScheduleParser.parseTime(prefs.dayStart) ?: LocalTime.of(8, 0)
        val end = ScheduleParser.parseTime(prefs.dayEnd) ?: LocalTime.of(22, 0)
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) {
                    FreeTime.compute(CalendarRepo.busy(ctx, from, to), from, to, start, end, prefs.minFree)
                }
                status = ""
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            }
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) compute() else status = "Нужен доступ к календарю."
    }
    fun ask() = if (hasPerms(ctx, CAL_PERMS)) compute() else perm.launch(CAL_PERMS)

    fun pick(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Свободным считается окно от ${prefs.minFree} мин в рамках ${prefs.dayStart}–${prefs.dayEnd}",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            query, { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Вопрос") },
            placeholder = { Text("когда я свободен 20–22 октября? / завтра / в пятницу") },
        )
        Button(
            onClick = {
                val ds = ScheduleParser.datesIn(query, LocalDate.now())
                if (ds.isEmpty()) status = "Не нашёл в вопросе дат. Пример: «свободное время 20.10–23.10»."
                else { from = ds.minBy { it.toEpochDay() }; to = ds.maxBy { it.toEpochDay() }; ask() }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Ответить") }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { from = LocalDate.now(); to = from; ask() }) { Text("Сегодня") }
            OutlinedButton(onClick = { from = LocalDate.now().plusDays(1); to = from; ask() }) { Text("Завтра") }
            OutlinedButton(onClick = { from = LocalDate.now(); to = from.plusDays(6); ask() }) { Text("7 дней") }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pick(from) { from = it; if (to.isBefore(it)) to = it } }) { Text("С ${from.format(DATE_FMT)}") }
            TextButton(onClick = { pick(to) { to = it } }) { Text("по ${to.format(DATE_FMT)}") }
            TextButton(onClick = { ask() }) { Text("Показать") }
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)

        result?.forEach { (day, slots) ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(day.format(DAY_FMT).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
            if (slots.isEmpty()) {
                Text("Свободных окон нет", style = MaterialTheme.typography.bodyMedium)
            } else {
                slots.forEach { s ->
                    Text(
                        "${s.start.format(TIME_FMT)}–${s.end.format(TIME_FMT)}  (${FreeTime.fmtDuration(s.minutes)})",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

// ---------------- вкладка «Настройки» ----------------

@Composable
fun SettingsTab(prefs: Prefs) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(prefs.mode) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var dayStart by remember { mutableStateOf(prefs.dayStart) }
    var dayEnd by remember { mutableStateOf(prefs.dayEnd) }
    var minFree by remember { mutableStateOf(prefs.minFree.toString()) }
    var cals by remember { mutableStateOf<List<CalInfo>>(emptyList()) }
    var calId by remember { mutableStateOf(prefs.calendarId) }

    fun loadCals() { cals = runCatching { CalendarRepo.writableCalendars(ctx) }.getOrDefault(emptyList()) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) loadCals()
    }
    LaunchedEffect(Unit) { if (hasPerms(ctx, CAL_PERMS)) loadCals() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Распознавание", style = MaterialTheme.typography.titleMedium)
        listOf(
            "offline" to "Офлайн на телефоне (Tesseract, бесплатно)",
            "claude" to "Claude API (точнее, нужен интернет и ключ)",
        ).forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(mode == value) { mode = value; prefs.mode = value },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == value, onClick = { mode = value; prefs.mode = value })
                Text(label)
            }
        }
        if (mode == "claude") {
            OutlinedTextField(
                key, { key = it; prefs.apiKey = it }, label = { Text("API-ключ (sk-ant-…)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                model, { model = it; prefs.model = it }, label = { Text("Модель") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Свободное время", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                dayStart, { dayStart = it; if (ScheduleParser.parseTime(it) != null) prefs.dayStart = it },
                label = { Text("День с") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                dayEnd, { dayEnd = it; if (ScheduleParser.parseTime(it) != null) prefs.dayEnd = it },
                label = { Text("до") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                minFree, { minFree = it; it.toIntOrNull()?.takeIf { v -> v > 0 }?.let { v -> prefs.minFree = v } },
                label = { Text("Мин. окно, мин") }, singleLine = true, modifier = Modifier.weight(1.2f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Календарь для новых событий", style = MaterialTheme.typography.titleMedium)
        if (cals.isEmpty()) {
            Text(
                "Если календарей нет, при первом сохранении создастся локальный «Мои планы».",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { if (hasPerms(ctx, CAL_PERMS)) loadCals() else perm.launch(CAL_PERMS) }) {
                Text("Показать календари")
            }
        }
        cals.forEach { c ->
            Row(
                Modifier.fillMaxWidth().selectable(calId == c.id) { calId = c.id; prefs.calendarId = c.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = calId == c.id, onClick = { calId = c.id; prefs.calendarId = c.id })
                Column {
                    Text(c.name)
                    if (c.account.isNotBlank()) Text(c.account, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
