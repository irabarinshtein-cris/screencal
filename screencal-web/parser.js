// СкринКалендарь — разбор расписаний (работает и в браузере, и в Node для тестов).
// Даты — строки 'YYYY-MM-DD', время — минуты от полуночи.

const pad = (n) => String(n).padStart(2, '0');

// ---------------- даты и время ----------------

export const D = {
  of(y, m, d) {
    if (!(m >= 1 && m <= 12 && d >= 1 && d <= 31)) return null;
    const x = new Date(Date.UTC(y, m - 1, d));
    if (x.getUTCFullYear() !== y || x.getUTCMonth() !== m - 1 || x.getUTCDate() !== d) return null;
    return x.toISOString().slice(0, 10);
  },
  parts(s) { const [y, m, d] = s.split('-').map(Number); return { y, m, d }; },
  add(s, n) { const x = new Date(s + 'T00:00:00Z'); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); },
  addMonths(s, n) {
    const { y, m, d } = D.parts(s);
    const x = new Date(Date.UTC(y, m - 1 + n, 1));
    const last = new Date(Date.UTC(x.getUTCFullYear(), x.getUTCMonth() + 1, 0)).getUTCDate();
    return D.of(x.getUTCFullYear(), x.getUTCMonth() + 1, Math.min(d, last));
  },
  /** 1 = понедельник … 7 = воскресенье */
  dow(s) { const g = new Date(s + 'T00:00:00Z').getUTCDay(); return g === 0 ? 7 : g; },
  epoch(s) { return Math.round(Date.parse(s + 'T00:00:00Z') / 86400000); },
  today() { const n = new Date(); return D.of(n.getFullYear(), n.getMonth() + 1, n.getDate()); },
  monday(s) { return D.add(s, 1 - D.dow(s)); },
  nextOrSame(s, dow) { return D.add(s, (dow - D.dow(s) + 7) % 7); },
  lastOfMonth(y, m) { return D.of(y, m, new Date(Date.UTC(y, m, 0)).getUTCDate()); },
  /** 'дд.мм.гггг' */
  ru(s) { const { y, m, d } = D.parts(s); return `${pad(d)}.${pad(m)}.${y}`; },
  fromRu(str) {
    const m = /^\s*(\d{1,2})[./](\d{1,2})[./](\d{4})\s*$/.exec(str || '');
    return m ? D.of(+m[3], +m[2], +m[1]) : null;
  },
};

export const WD_FULL = ['', 'Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота', 'Воскресенье'];
export const WD_SHORT = ['', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'];

export const T = {
  fmt(min) { return min == null ? '' : `${pad(Math.floor(min / 60) % 24)}:${pad(min % 60)}`; },
  parse(s) {
    const m = /^\s*([01]?\d|2[0-3])[:.]([0-5]\d)\s*$/.exec(s || '');
    return m ? +m[1] * 60 + +m[2] : null;
  },
};

// ---------------- шаблоны ----------------

const TM = '([01]?\\d|2[0-3])[:.]([0-5]\\d)';
const RANGE = new RegExp(`(?<!\\d)(?:(?<!\\p{L})с\\s*)?${TM}\\s*(?:-|–|—|до|по)\\s*${TM}(?!\\d)`, 'gu');
const ISO_DATE = /(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)/gu;
const FULL_DATE = /(?<!\d)(\d{1,2})[./](\d{1,2})[./](\d{4}|\d{2})(?!\d)/gu;
const MONTHS = 'январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|ма[йя]\\p{L}*|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|' +
  'сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*|янв|фев|мар|апр|авг|сент|сен|окт|ноя|дек';
const TEXT_DATE = new RegExp(`(?<!\\d)(\\d{1,2})\\s*(${MONTHS})\\.?(?:\\s*(\\d{4}))?`, 'gu');
const TEXT_DAY_RANGE = new RegExp(`(?<!\\d)(\\d{1,2})\\s*(?:-|–|—|по)\\s*(\\d{1,2})\\s*(${MONTHS})`, 'gu');
const SHORT_DATE = /(?<!\d)(\d{1,2})[./](\d{1,2})(?!\d)/gu;
const SINGLE_TIME = /(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)/gu;
const WEEKDAY = /(?<!\p{L})(понедельник\p{L}*|вторник\p{L}*|сред[аеуы]|четверг\p{L}*|пятниц[аеуы]|суббот[аеуы]|воскресень[ея]|пн|вт|ср|чт|пт|сб|вс|mon|tue|wed|thu|fri|sat|sun)(?!\p{L})/gu;
const RELATIVE = /(?<!\p{L})(сегодня|послезавтра|завтра)(?!\p{L})/gu;
const WEEK = /(?<!\p{L})(недел[юяеи]|7 дней)(?!\p{L})/gu;

// применяются к тексту в нижнем регистре
const TYPE = /(лекци|практик|лаборатор|семинар|зач[её]т|экзамен|консультац|комисси|пересдач)/u;
const ROOM = /^(?:[а-яёa-z]{1,2}-?\d{1,4}[а-я]?(?![\d:.])|\d-\d{2,4}[а-я]?(?:\s*\(|$)|.*(?<!\p{L})(?:ауд|каб|кабинет|аудитория)\.?\s*№?\s*\d|спортзал)/u;
const PERSON = /\p{Lu}\.\s?\p{Lu}\.|^\p{Lu}\p{Ll}+\s+\p{Lu}\p{Ll}+\s+\p{Lu}\p{Ll}+(вич|вна|ична|чна|оглы|кызы)(?!\p{L})/u;

function month(w) {
  return { янв: 1, фев: 2, мар: 3, апр: 4, май: 5, мая: 5, июн: 6, июл: 7, авг: 8, сен: 9, окт: 10, ноя: 11, дек: 12 }[w.slice(0, 3)] || 0;
}
function dow(w) {
  if (w.startsWith('пон') || w === 'пн' || w === 'mon') return 1;
  if (w.startsWith('вт') || w === 'tue') return 2;
  if (w.startsWith('ср') || w === 'wed') return 3;
  if (w.startsWith('чет') || w === 'чт' || w === 'thu') return 4;
  if (w.startsWith('пят') || w === 'пт' || w === 'fri') return 5;
  if (w.startsWith('суб') || w === 'сб' || w === 'sat') return 6;
  return 7;
}
function relative(w, today) { return w === 'сегодня' ? today : w === 'завтра' ? D.add(today, 1) : D.add(today, 2); }
const year2 = (s) => { const v = +s; return v < 100 ? 2000 + v : v; };

/** Без года — ближайший к сегодняшнему дню (прошлое не дальше 2 месяцев). */
function withYear(d, m, y, today) {
  if (!(m >= 1 && m <= 12 && d >= 1 && d <= 31)) return null;
  if (y) return D.of(year2(y), m, d);
  const c = D.of(D.parts(today).y, m, d);
  if (!c) return null;
  return c < D.addMonths(today, -2) ? D.of(D.parts(c).y + 1, m, d) : c;
}

function clean(s) {
  return (' ' + s + ' ').replace(/(?<=\s)[-–—|•·*,;:=]+(?=\s)/gu, ' ').replace(/\s+/gu, ' ')
    .replace(/^[\s\-–—|:;,.•·*=]+|[\s\-–—|:;,.•·*=]+$/gu, '');
}

class Line {
  constructor(orig) {
    this.low = orig.toLowerCase();
    this.base = this.low.length === orig.length ? orig : this.low;
    this.used = new Array(this.low.length).fill(false);
  }
  take(re) {
    const res = [];
    re.lastIndex = 0;
    for (const m of this.low.matchAll(re)) {
      const a = m.index, b = m.index + m[0].length;
      let busy = false;
      for (let i = a; i < b; i++) if (this.used[i]) { busy = true; break; }
      if (busy) continue;
      for (let i = a; i < b; i++) this.used[i] = true;
      res.push(m);
    }
    return res;
  }
  rest() { let s = ''; for (let i = 0; i < this.base.length; i++) s += this.used[i] ? ' ' : this.base[i]; return s; }
}

/** Разбор строки: дата, времена и остаток текста. */
export function lineInfo(raw, today) {
  const l = new Line(raw);
  const times = []; // [pos, start, end]
  const singles = [];
  let date = null;
  for (const m of l.take(RANGE)) times.push([m.index, +m[1] * 60 + +m[2], +m[3] * 60 + +m[4]]);
  for (const m of l.take(ISO_DATE)) date = date || D.of(+m[1], +m[2], +m[3]);
  for (const m of l.take(FULL_DATE)) date = date || D.of(year2(m[3]), +m[2], +m[1]);
  for (const m of l.take(TEXT_DATE)) date = date || withYear(+m[1], month(m[2]), m[3], today);
  for (const m of l.take(SINGLE_TIME)) singles.push([m.index, +m[1] * 60 + +m[2]]);
  for (const m of l.take(SHORT_DATE)) {
    const a = +m[1], b = +m[2];
    const asDate = date ? null : withYear(a, b, null, today);
    if (asDate) date = asDate;
    else if (a <= 23 && b <= 59) singles.push([m.index, a * 60 + b]);
  }
  const rel = l.take(RELATIVE), wd = l.take(WEEKDAY);
  if (!date && rel.length) date = relative(rel[0][1], today);
  if (!date && wd.length) date = D.nextOrSame(today, dow(wd[0][1]));
  singles.sort((a, b) => a[0] - b[0]);
  if (!times.length && singles.length === 2 && singles[1][1] > singles[0][1]) times.push([singles[0][0], singles[0][1], singles[1][1]]);
  else for (const s of singles) times.push([s[0], s[1], null]);
  times.sort((a, b) => a[0] - b[0]);
  return { date, times: times.map((t) => [t[1], t[2]]), rest: clean(l.rest()) };
}

// ---------------- исправление OCR ----------------

export function isCyrillic(text) {
  let c = 0, l = 0;
  for (const ch of text) { if (/[а-яёА-ЯЁ]/.test(ch)) c++; else if (/[a-zA-Z]/.test(ch)) l++; }
  return c > l * 2;
}

const HOMO = { A: 'А', B: 'В', C: 'С', E: 'Е', H: 'Н', K: 'К', M: 'М', O: 'О', P: 'Р', T: 'Т', X: 'Х', Y: 'У', U: 'И', N: 'Л', a: 'а', c: 'с', e: 'е', o: 'о', p: 'р', x: 'х', y: 'у', u: 'и', n: 'п' };

/** Латинские «двойники» в русском тексте (A-245 → А-245, MuC3UBKC → МиСЗИВКС). */
export function fixHomoglyphs(s) {
  return s.replace(/[A-Za-z0-9]+/g, (w) => {
    if (!/[A-Za-z]/.test(w)) return w;
    if ([...w].some((ch) => /[A-Za-z]/.test(ch) && !HOMO[ch])) return w;
    return [...w].map((ch, i) => (ch === '3' && (/[A-Za-z]/.test(w[i - 1] || '') || /[A-Za-z]/.test(w[i + 1] || '')) ? 'З' : HOMO[ch] || ch)).join('');
  });
}

/** Значок-иконка, прочитанный как один символ в начале строки («а УиАИС» → «УиАИС»). */
export function stripIcon(s) {
  const t = s.trim();
  const first = t.split(' ')[0];
  return first.length === 1 && t.length > 2 && !/\d/.test(first) ? clean(t.slice(1)) : t;
}

export const isRoom = (s) => ROOM.test(s.trim().toLowerCase());
const isType = (s) => TYPE.test(s.toLowerCase());
const isPerson = (s) => PERSON.test(s);
/** Зачёты, экзамены, комиссии, пересдачи — разовые, даже в расписании пар. */
export const isOneOff = (title) => /(комисси|пересдач|зач[её]т|экзамен|консультац)/u.test(title.toLowerCase());

// ---------------- лента событий (даты-заголовки, события под ними) ----------------

export function parseList(text, today) {
  const fix = isCyrillic(text);
  const out = [];
  let ctxDate = null, last = [], pending = [], extra = 0, cont = false, orphan = null;
  for (const raw of text.split('\n')) {
    const line = (fix ? fixHomoglyphs(raw) : raw).trim();
    if (!line) continue;
    const info = lineInfo(line, today);
    if (info.date) ctxDate = info.date;
    if (info.times.length) {
      const date = ctxDate || today;
      const title = stripIcon(info.rest).replace(/(^|\s)(в|во|с|со|на|к|ко)$/u, '').trim();
      const created = info.times.map(([s, e]) => ({ date, start: s, end: e, title, location: '', notes: [], orphan }));
      out.push(...created);
      orphan = null; last = created; pending = created.filter((e) => !e.title); extra = 2; cont = true;
      continue;
    }
    if (info.date) { last = []; pending = []; orphan = null; continue; }
    const r = info.rest;
    if (!/[\p{L}\p{N}]/u.test(r)) continue;
    if (pending.length) { pending.forEach((e) => (e.title = stripIcon(r))); pending = []; }
    else if (last.length && cont && /^\p{Ll}/u.test(r) && !isRoom(r)) last.forEach((e) => (e.title += ' ' + r));
    else if (last.length && extra > 0) {
      cont = false; extra--;
      last.forEach((e) => { if (isRoom(r) && !e.location) e.location = r; else e.notes.push(r); });
    } else { orphan = r; last = []; }
  }
  const seen = new Set();
  return out.map((e) => ({ date: e.date, start: e.start, end: e.end, title: e.title || e.orphan || 'Событие', location: e.location, note: e.notes.join('\n') }))
    .filter((e) => { const k = `${e.date}|${e.start}|${e.title.toLowerCase()}`; if (seen.has(k)) return false; seen.add(k); return true; });
}

// ---------------- ячейка таблицы-сетки ----------------

const W_LIST = /на\s*((?:\d{1,2}\s*,\s*)+\d{1,2})\s*нед/u;
const W_RANGE = /\((\d{1,2})\s*[-–—]\s*(\d{1,2})|(\d{1,2})\s*[-–—]\s*(\d{1,2})\s*нед/u;
const W_SINGLE = /на\s*(\d{1,2})\s*не/u;

/** Недели из пометок: «2 – 16 нед.» → [2,16], «на 8 нед.» → [8,8], «на 2, 4, 6 нед.» → [2,6]. */
export function weekSpan(lines) {
  for (const raw of lines) {
    const l = raw.toLowerCase();
    let m = W_LIST.exec(l);
    if (m) { const ws = m[1].split(',').map((x) => parseInt(x, 10)).filter((x) => !isNaN(x)); if (ws.length) return [Math.min(...ws), Math.max(...ws)]; }
    m = W_RANGE.exec(l);
    if (m) { const a = +(m[1] || m[3]), b = +(m[2] || m[4]); if (a >= 1 && a <= 30 && b >= a && b <= 30) return [a, b]; }
    m = W_SINGLE.exec(l);
    if (m) { const k = +m[1]; if (k >= 1 && k <= 30) return [k, k]; }
  }
  return null;
}

/** Номер учебной недели со скрина («№8, чётная», «8-я неделя»). */
export function weekNumberIn(text) {
  const m = /№\s*(\d{1,2})(?!\d)|(?<!\d)(\d{1,2})\s*(?:-?я)?\s*(?:учебная\s+)?неделя/u.exec(text.toLowerCase());
  if (!m) return null;
  const n = +(m[1] || m[2]);
  return n >= 1 && n <= 30 ? n : null;
}

function shortType(s) {
  return s.split('(')[0].replace(/\s*[+«»*•·]\s*/gu, ' • ').replace(/(\.\.\.|…)$/u, '').trim();
}

/** Ячейка: «Предмет / Преподаватель / Тип занятия / Аудитория / 09:30 – 11:00». */
export function parseCell(lines, date, today, dayOffset) {
  const infos = lines.map((l) => lineInfo(l, today));
  const ti = infos.findIndex((i) => !i.date && i.times.some((t) => t[1] != null));
  if (ti < 0) return null;
  const [start, end] = infos[ti].times.find((t) => t[1] != null);
  const texts = lines.map((l, i) => (i === ti ? infos[i].rest : clean(l))).map(stripIcon)
    .map((s) => s.replace(/^\d\s+(?=\p{L}{2})/u, ''))
    .filter((s) => (s.match(/\p{L}/gu) || []).length >= 2 || (/\p{L}/u.test(s) && /\d/.test(s)));
  const roomRaw = texts.find(isRoom) || '';
  const room = roomRaw.replace(/^1-(?=\d)/u, 'Л-'); // «Л» часто читается как «1»
  const typeLine = texts.find((t) => t !== roomRaw && isType(t));
  const subject = texts.find((t) => t !== roomRaw && t !== typeLine && !isPerson(t));
  const type = typeLine ? shortType(typeLine) : '';
  const notes = texts.filter((t) => t !== roomRaw && t !== typeLine && t !== subject);
  const title = [subject || '', type].filter(Boolean).join(' — ') || 'Занятие';
  const span = weekSpan(lines.filter((_, i) => i !== ti));
  return { date, start, end, title, location: room, note: notes.join('\n'), dayOffset, weekFrom: span ? span[0] : null, weekTo: span ? span[1] : null };
}

// ---------------- разметка скрина по координатам слов ----------------

/** Неделя по умолчанию: в выходные — следующая, в будни — текущая. */
export function defaultWeekStart(today) {
  const w = D.dow(today);
  return w >= 6 ? D.add(today, 8 - w) : D.monday(today);
}

/** words: [{text, l, t, r, b, line}] → куски строк (колонки таблицы разделяются большим пробелом). */
export function segments(words, W, H) {
  const fix = isCyrillic(words.map((w) => w.text).join(' '));
  const byLine = new Map();
  for (const w of words) { if (!byLine.has(w.line)) byLine.set(w.line, []); byLine.get(w.line).push(w); }
  const res = [];
  for (const ws0 of byLine.values()) {
    const ws = [...ws0].sort((a, b) => a.l - b.l);
    const hs = ws.map((w) => w.b - w.t).sort((a, b) => a - b);
    const h = Math.max(hs[Math.floor(hs.length / 2)], 8);
    let cur = [ws[0]];
    const flush = () => {
      let text = cur.map((w) => w.text).join(' ');
      if (fix) text = fixHomoglyphs(text);
      if (/[\p{L}\p{N}]/u.test(text)) res.push({ text, l: Math.min(...cur.map((w) => w.l)), t: Math.min(...cur.map((w) => w.t)), r: Math.max(...cur.map((w) => w.r)), b: Math.max(...cur.map((w) => w.b)) });
    };
    for (const w of ws.slice(1)) {
      if (w.l - cur[cur.length - 1].r > 2.5 * h) { flush(); cur = [w]; } else cur.push(w);
    }
    flush();
  }
  const portrait = H > W * 1.5;
  return res.filter((s) => !(portrait && s.b < H * 0.035)); // строка состояния телефона
}

/**
 * Таблица-сетка (колонки = дни, ячейки = пары) — каждая ячейка отдельно;
 * лента/список — построчно сверху вниз.
 */
export function extract(words, W, H, today, weekStart) {
  if (!words.length) return { events: [], text: '', datesGuessed: false, isSchedule: false, weekNumber: null };
  const segs = segments(words, W, H);
  const listText = [...segs].sort((a, b) => a.t - b.t || a.l - b.l).map((s) => s.text).join('\n');
  const weekNumber = weekNumberIn(listText);
  const looksLikePairs = /(расписани|нед\.|лекци|семинар|практик)/u.test(listText.toLowerCase());

  const tol = Math.max(Math.round(W * 0.015), 8);
  const timeLefts = segs.filter((s) => lineInfo(s.text, today).times.length).map((s) => s.l).sort((a, b) => a - b);
  const clusters = [];
  for (const x of timeLefts) {
    if (!clusters.length || x - clusters[clusters.length - 1].at(-1) > tol) clusters.push([x]); else clusters[clusters.length - 1].push(x);
  }
  const anchors = clusters.filter((c) => c.length >= 2).map((c) => Math.min(...c));
  if (anchors.length < 2 || timeLefts.length < 4) {
    return { events: parseList(listText, today), text: listText, datesGuessed: false, isSchedule: looksLikePairs, weekNumber };
  }

  const diffs = anchors.slice(1).map((a, i) => a - anchors[i]);
  const minDiff = Math.min(...diffs);
  const near = diffs.filter((d) => d < minDiff * 1.3);
  const pitch = near.reduce((a, b) => a + b, 0) / near.length;
  const a0 = anchors[0];

  const columns = new Map();
  for (const s of segs) {
    if (s.l < a0 - tol || s.r - s.l > pitch * 1.2) continue; // подписи на всю ширину
    const k = Math.floor((s.l - a0 + tol) / pitch);
    if (Math.abs(s.l - (a0 + k * pitch)) > pitch * 0.6) continue;
    if (!columns.has(k)) columns.set(k, []);
    columns.get(k).push(s);
  }
  const keys = [...columns.keys()].sort((a, b) => a - b);

  // даты колонок: явная дата в заголовке, иначе — по порядку колонок
  let base = null;
  const offsets = new Map();
  for (const k of keys) {
    for (const s of [...columns.get(k)].sort((a, b) => a.t - b.t)) {
      const info = lineInfo(s.text, today);
      if (info.times.length) break;
      if (/\d/.test(s.text) && info.date && !base) base = D.add(info.date, -k);
      const wm = new Line(s.text).take(WEEKDAY);
      if (wm.length) offsets.set(k, dow(wm[0][1]) - 1);
    }
  }
  const guessed = !base;
  const start = base || weekStart;

  const events = [];
  let text = '';
  for (const k of keys) {
    const sorted = [...columns.get(k)].sort((a, b) => a.t - b.t);
    const hs = sorted.map((s) => s.b - s.t).sort((a, b) => a - b);
    const medH = hs[Math.floor(hs.length / 2)];
    const cells = [[sorted[0]]];
    for (const s of sorted.slice(1)) {
      const cell = cells[cells.length - 1];
      if (s.t - Math.max(...cell.map((c) => c.b)) > medH) cells.push([s]); else cell.push(s);
    }
    const offset = guessed ? (offsets.has(k) ? offsets.get(k) : k) : k;
    text += `— колонка ${k + 1} —\n`;
    for (const cell of cells) {
      const lines = cell.map((c) => c.text);
      text += lines.join('\n') + '\n\n';
      const ev = parseCell(lines, D.add(start, offset), today, guessed ? offset : null);
      if (ev) events.push(ev);
    }
  }
  events.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.start - b.start));
  return { events, text: text.trim(), datesGuessed: guessed, isSchedule: true, weekNumber };
}

// ---------------- вопросы о свободном времени ----------------

export function datesIn(text, today) {
  const l = new Line(text);
  const res = [];
  for (const m of l.take(TEXT_DAY_RANGE)) {
    const mo = month(m[3]);
    const a = withYear(+m[1], mo, null, today), b = withYear(+m[2], mo, null, today);
    if (a && b) res.push(a, b);
  }
  for (const m of l.take(ISO_DATE)) { const d = D.of(+m[1], +m[2], +m[3]); if (d) res.push(d); }
  for (const m of l.take(FULL_DATE)) { const d = D.of(year2(m[3]), +m[2], +m[1]); if (d) res.push(d); }
  for (const m of l.take(TEXT_DATE)) { const d = withYear(+m[1], month(m[2]), m[3], today); if (d) res.push(d); }
  for (const m of l.take(SHORT_DATE)) { const d = withYear(+m[1], +m[2], null, today); if (d) res.push(d); }
  for (const m of l.take(RELATIVE)) res.push(relative(m[1], today));
  for (const m of l.take(WEEKDAY)) res.push(D.nextOrSame(today, dow(m[1])));
  if (l.take(WEEK).length) res.push(today, D.add(today, 6));
  return res;
}

/**
 * Свободные окна по дням. busy — [[startMs, endMs]] (локальное время браузера),
 * dayStart/dayEnd — минуты, minMinutes — минимальная длина окна.
 */
export function freeSlots(busy, from, to, dayStart, dayEnd, minMinutes) {
  const out = [];
  for (let day = from; day <= to; day = D.add(day, 1)) {
    const { y, m, d } = D.parts(day);
    const ws = new Date(y, m - 1, d, 0, dayStart).getTime();
    const we = dayEnd <= dayStart ? new Date(y, m - 1, d + 1).getTime() : new Date(y, m - 1, d, 0, dayEnd).getTime();
    const clipped = busy.filter(([s, e]) => e > ws && s < we).map(([s, e]) => [Math.max(s, ws), Math.min(e, we)]).sort((a, b) => a[0] - b[0]);
    const slots = [];
    let cur = ws;
    const add = (s, e) => {
      if ((e - s) / 60000 < minMinutes) return;
      const sd = new Date(s), ed = new Date(e);
      const endMin = ed.getDate() !== sd.getDate() ? 24 * 60 : ed.getHours() * 60 + ed.getMinutes();
      slots.push({ start: sd.getHours() * 60 + sd.getMinutes(), end: endMin, minutes: Math.round((e - s) / 60000) });
    };
    for (const [s, e] of clipped) { if (s > cur) add(cur, s); if (e > cur) cur = e; }
    if (we > cur) add(cur, we);
    out.push({ day, slots });
  }
  return out;
}

export function fmtDuration(min) {
  const h = Math.floor(min / 60), m = min % 60;
  return h === 0 ? `${m} мин` : m === 0 ? `${h} ч` : `${h} ч ${m} мин`;
}

// ---------------- повторы ----------------

export const REPEATS = {
  none: { label: 'Не повторять', rule: null },
  daily: { label: 'Ежедневно', rule: 'FREQ=DAILY' },
  weekly: { label: 'Еженедельно', rule: 'FREQ=WEEKLY;WKST=MO' },
  biweekly: { label: 'Раз в 2 недели', rule: 'FREQ=WEEKLY;INTERVAL=2;WKST=MO' },
  monthly: { label: 'Ежемесячно', rule: 'FREQ=MONTHLY' },
  yearly: { label: 'Ежегодно', rule: 'FREQ=YEARLY' },
};

const utcStamp = (ms) => new Date(ms).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');

/** RRULE с последней датой повтора (включительно, локальный конец дня) или бессрочно. */
export function rrule(repeat, until) {
  const r = (REPEATS[repeat] || REPEATS.none).rule;
  if (!r) return null;
  if (!until) return r;
  const { y, m, d } = D.parts(until);
  return `${r};UNTIL=${utcStamp(new Date(y, m - 1, d, 23, 59, 59).getTime())}`;
}

/** Обрезает серию: повторы только до момента endMs. */
export function cutRule(rule, endMs) {
  const r = rule.replace(/^RRULE:/, '').split(';').filter((p) => p && !p.startsWith('UNTIL=') && !p.startsWith('COUNT=')).join(';');
  return `RRULE:${r};UNTIL=${utcStamp(endMs)}`;
}

/** Чётность недели по календарю (0/1) — отличает чётное и нечётное расписание. */
export function parity(date) {
  const w = Math.floor((D.epoch(date) + 3) / 7);
  return ((w % 2) + 2) % 2;
}

/**
 * Пара из расписания: повтор раз в 2 недели с учётом «(2–16 нед.)», «(на 8 нед.)».
 * Возвращает {first, repeat, last} или null, если пара уже закончилась.
 */
export function planPair(base, weekNo, weekFrom, weekTo, oneOff) {
  if (weekNo == null || weekTo == null) return { first: base, repeat: oneOff ? 'none' : 'biweekly', last: null };
  if (weekTo < weekNo) return null;
  const from = Math.max(weekFrom ?? weekNo, weekNo);
  const first = D.add(base, (from - weekNo) * 7);
  const last = D.add(base, (weekTo - weekNo) * 7);
  return last > first ? { first, repeat: 'biweekly', last } : { first, repeat: 'none', last: null };
}

// ---------------- указания текстом (без нейросети) ----------------

const WD_PLURAL = { понедельникам: 'понедельник', вторникам: 'вторник', средам: 'среда', четвергам: 'четверг', пятницам: 'пятница', субботам: 'суббота', воскресеньям: 'воскресенье' };

/** Убирает из текста указания о повторе и сроке, чтобы они не стали названием или датой события. */
export function stripInstructions(text) {
  let t = ' ' + (text || '') + ' ';
  t = t.replace(/\s(?:каждый|каждую|каждое)\s+(понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)/giu, (_, w) => ' ' + w);
  t = t.replace(/\sпо\s+(понедельникам|вторникам|средам|четвергам|пятницам|субботам|воскресеньям)/giu, (_, w) => ' ' + WD_PLURAL[w.toLowerCase()]);
  t = t.replace(new RegExp(`\\sдо\\s+(?:конца\\s+\\p{L}+|\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?|\\d{1,2}\\s*(?:${MONTHS})\\.?(?:\\s*\\d{4})?)`, 'giu'), ' ');
  t = t.replace(/\s(?:повторять|повтор|каждую\s+неделю|еженедельно|каждый\s+день|ежедневно|раз\s+в\s+(?:две|2)\s+недели|через\s+неделю|каждый\s+месяц|ежемесячно|каждый\s+год|ежегодно|разово|один\s+раз)(?=[\s,.;])/giu, ' ');
  return t.replace(/\s+/g, ' ').trim();
}

/**
 * «каждую неделю до 20 декабря», «ежедневно», «раз в две недели», «разово», «это расписание пар», «неделя №8».
 * Возвращает только то, что найдено: {repeat, until, mode, weekNumber}.
 */
export function parseInstructions(text, today) {
  const t = (text || '').toLowerCase();
  const res = {};
  if (!t.trim()) return res;
  if (/(разов|один раз|не повтор|только на (этот )?день)/u.test(t)) res.repeat = 'none';
  else if (/(каждый день|ежедневн|каждодневн)/u.test(t)) res.repeat = 'daily';
  else if (/(раз в (две|2) недел|через неделю|кажд\p{L}* (втор\p{L}*|2) недел|по ч[её]тн|по неч[её]тн|ч[её]тн\p{L}* и неч[её]тн)/u.test(t)) res.repeat = 'biweekly';
  else if (/(каждую неделю|еженедельн|каждый (понедельник|вторник|четверг)|каждую (среду|пятницу|субботу)|каждое воскресенье|по (понедельникам|вторникам|средам|четвергам|пятницам|субботам|воскресеньям))/u.test(t)) res.repeat = 'weekly';
  else if (/(каждый месяц|ежемесячн)/u.test(t)) res.repeat = 'monthly';
  else if (/(каждый год|ежегодн)/u.test(t)) res.repeat = 'yearly';

  const um = /(?:^|\s)до\s+(.{1,40})/u.exec(t);
  if (um) {
    const tail = um[1];
    const endOf = new RegExp(`^конца\\s+(${MONTHS})`, 'u').exec(tail);
    if (endOf) {
      const mo = month(endOf[1]);
      let y = D.parts(today).y;
      if (mo < D.parts(today).m) y++;
      res.until = D.lastOfMonth(y, mo);
    } else if (/^конца\s+(месяца)/u.test(tail)) {
      const { y, m } = D.parts(today); res.until = D.lastOfMonth(y, m);
    } else if (/^конца\s+(года)/u.test(tail)) {
      res.until = D.of(D.parts(today).y, 12, 31);
    } else {
      const ds = datesIn(tail.split(/[,;]/)[0], today);
      if (ds.length) res.until = ds[0];
    }
  }
  if (/(расписани\p{L}* пар|это пары|учебн\p{L}* расписани)/u.test(t)) res.mode = 'schedule';
  else if (/(разовые|мероприяти)/u.test(t) && res.repeat === 'none') res.mode = 'once';
  const wn = weekNumberIn(t);
  if (wn) res.weekNumber = wn;
  return res;
}
