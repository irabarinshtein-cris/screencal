import * as P from './parser.js';

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Moscow';
const SCOPES = 'openid email https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.readonly';
const CAL = 'https://www.googleapis.com/calendar/v3';

// ---------------- настройки ----------------

const cfg = window.SC_CONFIG || {};
const store = {
  get(k, d) { try { const v = localStorage.getItem('sc.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('sc.' + k, JSON.stringify(v)); } catch { /* приватный режим */ } },
};
const S = {
  get clientId() { return store.get('clientId', '') || cfg.googleClientId || ''; },
  get proxy() { return store.get('proxy', '') || cfg.gigachatProxy || ''; },
  get appKey() { return store.get('appKey', ''); },
  get useAI() { return store.get('useAI', true) && !!this.proxy; },
  get writeCal() { return store.get('writeCal', 'primary'); },
  get busyCals() { return store.get('busyCals', null); },
  get dayStart() { return P.T.parse(store.get('dayStart', '08:00')) ?? 480; },
  get dayEnd() { return P.T.parse(store.get('dayEnd', '22:00')) ?? 1320; },
  get minFree() { return +store.get('minFree', 60) || 60; },
};

// ---------------- состояние ----------------

const st = {
  token: null, tokenExp: 0, email: store.get('email', ''),
  calendars: [],
  image: null,          // Blob
  mode: 'once',         // 'schedule' | 'once'
  weekStart: null,      // для таблиц без дат
  weekNo: null,
  drafts: [],
  busy: false,
};
let idSeq = 0;

// ---------------- Google: вход и API ----------------

function tokenValid() { return st.token && Date.now() < st.tokenExp - 60_000; }

/** Получить токен. Вызывать прямо из обработчика нажатия (иначе браузер заблокирует окно входа). */
function ensureToken() {
  if (tokenValid()) return Promise.resolve(st.token);
  if (!S.clientId) return Promise.reject(new Error('Не задан Google Client ID (config.js или Настройки → Дополнительно).'));
  if (!window.google?.accounts?.oauth2) return Promise.reject(new Error('Сервис входа Google ещё не загрузился — попробуйте через пару секунд.'));
  return new Promise((resolve, reject) => {
    const client = google.accounts.oauth2.initTokenClient({
      client_id: S.clientId,
      scope: SCOPES,
      login_hint: st.email || undefined,
      callback: async (r) => {
        if (r.error) { reject(new Error('Google: ' + r.error)); return; }
        st.token = r.access_token;
        st.tokenExp = Date.now() + (r.expires_in || 3600) * 1000;
        store.set('signed', true);
        resolve(st.token);
        afterLogin().catch(() => {});
      },
      error_callback: (e) => reject(new Error(e?.type === 'popup_closed' ? 'Окно входа закрыто.' : 'Не удалось войти в Google.')),
    });
    client.requestAccessToken({ prompt: store.get('signed', false) ? '' : 'consent' });
  });
}

async function gfetch(path, opts = {}) {
  const tok = await ensureToken();
  const r = await fetch(path.startsWith('http') ? path : CAL + path, {
    ...opts,
    headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json', ...(opts.headers || {}) },
  });
  if (r.status === 401) { st.token = null; throw new Error('Сессия Google истекла — нажмите кнопку ещё раз.'); }
  if (!r.ok) {
    let msg = r.status + '';
    try { msg = (await r.json()).error?.message || msg; } catch { /* */ }
    throw new Error('Google Календарь: ' + msg);
  }
  return r.status === 204 ? null : r.json();
}

async function afterLogin() {
  try {
    const u = await gfetch('https://www.googleapis.com/oauth2/v3/userinfo');
    if (u?.email) { st.email = u.email; store.set('email', u.email); }
  } catch { /* не критично */ }
  await loadCalendars();
  renderAccount();
}

async function loadCalendars() {
  const res = await gfetch('/users/me/calendarList?minAccessRole=reader&maxResults=250');
  st.calendars = res.items || [];
  renderCalendars();
}

function renderAccount() {
  const signed = tokenValid() || store.get('signed', false);
  $('#accountBtn').textContent = signed ? (st.email || 'Google: вход выполнен') : 'Войти через Google';
  $('#gState').textContent = !S.clientId
    ? 'Не задан Google Client ID — см. README (config.js).'
    : signed ? `Аккаунт: ${st.email || 'вход выполнен'}. События пишутся прямо в Google Календарь.` : 'Войдите, чтобы записывать события в Google Календарь.';
  $('#signOut').hidden = !signed;
}

function renderCalendars() {
  const sel = $('#writeCal');
  const writable = st.calendars.filter((c) => c.accessRole === 'owner' || c.accessRole === 'writer');
  sel.innerHTML = writable.map((c) => `<option value="${esc(c.id)}">${esc(c.summaryOverride || c.summary)}${c.primary ? ' (основной)' : ''}</option>`).join('')
    || '<option value="primary">Основной</option>';
  const w = S.writeCal;
  sel.value = writable.some((c) => c.id === w) ? w : (writable.find((c) => c.primary)?.id || 'primary');
  const chosen = S.busyCals;
  $('#busyCals').innerHTML = st.calendars.map((c) => {
    const on = chosen ? chosen.includes(c.id) : (c.selected !== false);
    return `<label><input type="checkbox" value="${esc(c.id)}" ${on ? 'checked' : ''}> ${esc(c.summaryOverride || c.summary)}</label>`;
  }).join('') || '<span class="small">календари не найдены</span>';
}

function busyCalendarIds() {
  const chosen = S.busyCals;
  if (chosen && chosen.length) return chosen;
  const sel = st.calendars.filter((c) => c.selected !== false).map((c) => c.id);
  return sel.length ? sel : ['primary'];
}

// ---------------- черновики событий ----------------

function draftFrom(e) {
  return {
    id: ++idSeq,
    title: e.title || '',
    date: e.date,
    start: typeof e.start === 'number' ? P.T.fmt(e.start) : (e.start || ''),
    end: typeof e.end === 'number' ? P.T.fmt(e.end) : (e.end || ''),
    location: e.location || '',
    note: e.note || '',
    checked: /\p{L}/u.test(e.title || ''),
    repeat: e.repeat || 'none',
    until: e.until || '',
    dayOffset: e.dayOffset ?? null,
    weekFrom: e.weekFrom ?? null,
    weekTo: e.weekTo ?? null,
    fromAI: !!e.fromAI,
  };
}

/** Пересчёт даты и повтора под режим (расписание пар / разовые). */
function planDraft(d) {
  const base = d.dayOffset != null && st.weekStart ? P.D.add(st.weekStart, d.dayOffset) : d.date;
  if (!base) return d;
  if (st.mode === 'once') return { ...d, date: base, repeat: 'none', until: '' };
  if (d.fromAI) return { ...d, date: base }; // нейросеть уже решила, что повторяется
  const p = P.planPair(base, st.weekNo, d.weekFrom, d.weekTo, P.isOneOff(d.title));
  if (!p) return { ...d, date: base, repeat: 'none', until: '', checked: false, note: (d.note ? d.note + '\n' : '') + 'Пара уже закончилась по номеру недели.' };
  return { ...d, date: p.first, repeat: p.repeat, until: p.last || '' };
}

function replan() { st.drafts = st.drafts.map(planDraft); renderCards(); }

/** Указания из текстового поля, разобранные без нейросети. */
function applyInstructions(text) {
  const ins = P.parseInstructions(text, P.D.today());
  if (ins.weekNumber) st.weekNo = ins.weekNumber;
  if (ins.mode) st.mode = ins.mode;
  st.drafts = st.drafts.map(planDraft);
  if (ins.repeat) st.drafts = st.drafts.map((d) => ({ ...d, repeat: ins.repeat, until: ins.repeat === 'none' ? '' : d.until }));
  if (ins.until) st.drafts = st.drafts.map((d) => (d.repeat !== 'none' ? { ...d, until: ins.until } : d));
  return ins;
}

// ---------------- распознавание ----------------

let worker = null;
async function getWorker(progress) {
  if (worker) return worker;
  const base = new URL('.', location.href);
  worker = await Tesseract.createWorker('rus+eng', 1, {
    workerPath: new URL('vendor/tesseract/worker.min.js', base).href,
    corePath: new URL('vendor/tesseract/', base).href,
    langPath: new URL('tessdata', base).href,
    gzip: false,
    logger: (m) => { if (m.status === 'recognizing text') progress(Math.round(m.progress * 100)); },
  });
  await worker.setParameters({ tessedit_pageseg_mode: '3', preserve_interword_spaces: '1' });
  return worker;
}

/** Серый цвет, ×2 для мелкого текста, инверсия тёмной темы. */
async function prepareCanvas(blob) {
  const bmp = await createImageBitmap(blob);
  let scale = Math.min(bmp.width, bmp.height) < 1300 ? 2 : 1;
  if (Math.max(bmp.width, bmp.height) * scale > 6000) scale = 6000 / Math.max(bmp.width, bmp.height);
  const W = Math.round(bmp.width * scale), H = Math.round(bmp.height * scale);
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, W, H);
  const img = ctx.getImageData(0, 0, W, H);
  const p = img.data;
  let sum = 0, n = 0;
  const step = Math.max(4, Math.floor(p.length / 4 / 1600)) * 4;
  for (let i = 0; i < p.length; i += step) { sum += 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2]; n++; }
  const invert = sum / n < 110;
  for (let i = 0; i < p.length; i += 4) {
    let g = 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];
    if (invert) g = 255 - g;
    p[i] = p[i + 1] = p[i + 2] = g;
  }
  ctx.putImageData(img, 0, 0);
  return { canvas: c, W, H };
}

async function ocr(blob, progress) {
  const { canvas, W, H } = await prepareCanvas(blob);
  const w = await getWorker(progress);
  const { data } = await w.recognize(canvas, {}, { blocks: true, text: true });
  const words = [];
  let line = 0;
  for (const b of data.blocks || []) for (const para of b.paragraphs) for (const l of para.lines) {
    line++;
    for (const wd of l.words) {
      const t = (wd.text || '').trim();
      if (t) words.push({ text: t, l: wd.bbox.x0, t: wd.bbox.y0, r: wd.bbox.x1, b: wd.bbox.y1, line });
    }
  }
  return { words, W, H, text: data.text || '' };
}

async function toJpegBase64(blob, max = 1600) {
  const bmp = await createImageBitmap(blob);
  const k = Math.min(1, max / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  const out = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.85));
  const buf = new Uint8Array(await out.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return btoa(s);
}

async function aiRecognize(blob, text) {
  const today = P.D.today();
  const body = { text, today, tz: TZ, weekStart: P.defaultWeekStart(today) };
  if (blob) body.image = await toJpegBase64(blob);
  const r = await fetch(S.proxy, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(S.appKey ? { 'X-App-Key': S.appKey } : {}) },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `прокси ответил ${r.status}`);
  return j;
}

async function recognize() {
  const text = $('#extra').value.trim();
  if (!st.image && !text) { setStatus('Загрузите скрин или напишите текст.', 'err'); return; }
  setBusy(true);
  const today = P.D.today();
  try {
    let usedAI = false;
    if (S.useAI && $('#useAI').checked) {
      try {
        setStatus('GigaChat читает…');
        const res = await aiRecognize(st.image, text);
        st.weekStart = null;
        st.weekNo = res.weekNumber ?? null;
        st.mode = res.kind === 'schedule' ? 'schedule' : 'once';
        st.drafts = (res.events || []).map((e) => draftFrom({ ...e, fromAI: true }));
        showRaw(res.raw || '');
        usedAI = true;
      } catch (e) {
        setStatus('GigaChat недоступен (' + e.message + '), распознаю офлайн…');
      }
    }
    if (!usedAI) {
      let ex;
      if (st.image) {
        setStatus('Распознаю текст на скрине…');
        const r = await ocr(st.image, (pc) => setStatus(`Распознаю текст на скрине… ${pc}%`));
        ex = r.words.length ? P.extract(r.words, r.W, r.H, today, P.defaultWeekStart(today))
          : { events: P.parseList(r.text, today), text: r.text, datesGuessed: false, isSchedule: false, weekNumber: null };
        if (text) ex.events.push(...P.parseList(P.stripInstructions(text), today));
      } else {
        ex = { events: P.parseList(P.stripInstructions(text), today), text, datesGuessed: false, isSchedule: false, weekNumber: null };
      }
      st.weekStart = ex.datesGuessed ? P.defaultWeekStart(today) : null;
      st.weekNo = ex.weekNumber;
      st.mode = ex.isSchedule ? 'schedule' : 'once';
      st.drafts = ex.events.map(draftFrom);
      showRaw(ex.text);
      applyInstructions(text);
    } else if (text) {
      // нейросеть уже учла текст; поправим только то, что указано явно
      const ins = P.parseInstructions(text, today);
      if (ins.mode) st.mode = ins.mode;
    }
    renderMode();
    renderWeek();
    renderCards();
    setStatus(st.drafts.length
      ? `Найдено: ${st.drafts.length}${usedAI ? ' (GigaChat)' : ''}. Проверьте карточки и нажмите кнопку внизу.`
      : 'События не найдены. Проверьте распознанный текст или добавьте вручную.', st.drafts.length ? 'ok' : 'err');
  } catch (e) {
    setStatus('Ошибка: ' + e.message, 'err');
  } finally {
    setBusy(false);
  }
}

// ---------------- запись в Google Календарь ----------------

function hash(s) { let h = 0x811c9dc5; for (const ch of s) { h ^= ch.codePointAt(0); h = Math.imul(h, 16777619); } return (h >>> 0).toString(36); }

function validate(d) {
  if (!d.title.trim()) return 'нет названия';
  if (!d.date) return 'нет даты';
  if (P.T.parse(d.start) == null) return 'нет времени начала';
  if (d.end && P.T.parse(d.end) == null) return 'неверное время конца';
  if (d.repeat !== 'none' && d.until && d.until < d.date) return '«повтор до» раньше даты';
  return null;
}

function eventBody(d, tag) {
  const s = P.T.parse(d.start);
  let e = d.end ? P.T.parse(d.end) : s + 60;
  let endDate = d.date;
  if (e <= s) { if (e < s) endDate = P.D.add(d.date, 1); else e = s + 60; }
  const key = 'k' + hash(`${d.date}|${d.start}|${d.title.trim().toLowerCase()}`);
  const priv = { sc: '1', sckey: key };
  if (tag) priv.scsched = tag;
  const rule = P.rrule(d.repeat, d.until || null);
  return {
    key,
    body: {
      summary: d.title.trim(),
      location: d.location.trim() || undefined,
      description: [d.note, 'Добавлено через СкринКалендарь'].filter(Boolean).join('\n'),
      start: { dateTime: `${d.date}T${P.T.fmt(s)}:00`, timeZone: TZ },
      end: { dateTime: `${endDate}T${P.T.fmt(e % 1440)}:00`, timeZone: TZ },
      recurrence: rule ? ['RRULE:' + rule] : undefined,
      extendedProperties: { private: priv },
    },
  };
}

/** Новое расписание заменяет прежнее той же чётности: старые серии обрываются перед новой неделей. */
async function cutSchedule(calId, tag, fromDate) {
  const { y, m, d } = P.D.parts(fromDate);
  const fromMs = new Date(y, m - 1, d).getTime();
  const list = await gfetch(`/calendars/${encodeURIComponent(calId)}/events?privateExtendedProperty=${encodeURIComponent('scsched=' + tag)}&maxResults=2500`);
  let n = 0;
  for (const it of list.items || []) {
    const startMs = Date.parse(it.start?.dateTime || it.start?.date);
    const path = `/calendars/${encodeURIComponent(calId)}/events/${encodeURIComponent(it.id)}`;
    if (startMs >= fromMs) { await gfetch(path, { method: 'DELETE' }); n++; }
    else if (it.recurrence?.length) {
      await gfetch(path, { method: 'PATCH', body: JSON.stringify({ recurrence: it.recurrence.map((r) => (r.startsWith('RRULE:') ? P.cutRule(r, fromMs - 1000) : r)) }) });
      n++;
    }
  }
  return n;
}

async function save() {
  const items = st.drafts.filter((d) => d.checked);
  if (!items.length) return;
  const bad = items.map((d) => [d, validate(d)]).filter(([, err]) => err);
  if (bad.length) {
    setStatus('Исправьте карточки: ' + bad.map(([d, err]) => `«${d.title || 'без названия'}» — ${err}`).join('; '), 'err');
    return;
  }
  const tokP = ensureToken(); // сразу в обработчике нажатия, иначе браузер заблокирует окно входа
  setBusy(true);
  try {
    await tokP;
    const calId = S.writeCal || 'primary';
    const schedule = st.mode === 'schedule';
    let replaced = 0;
    if (schedule) {
      const firstByTag = {};
      for (const d of items) {
        if (d.repeat === 'none') continue;
        const tag = 'P' + P.parity(d.date);
        const mon = P.D.monday(d.date);
        if (!firstByTag[tag] || mon < firstByTag[tag]) firstByTag[tag] = mon;
      }
      for (const [tag, from] of Object.entries(firstByTag)) replaced += await cutSchedule(calId, tag, from);
    }
    let added = 0, dup = 0;
    for (const d of items) {
      const tag = schedule && d.repeat !== 'none' ? 'P' + P.parity(d.date) : null;
      const { key, body } = eventBody(d, tag);
      const ex = await gfetch(`/calendars/${encodeURIComponent(calId)}/events?privateExtendedProperty=${encodeURIComponent('sckey=' + key)}&maxResults=1`);
      if (ex.items?.length) { dup++; continue; }
      await gfetch(`/calendars/${encodeURIComponent(calId)}/events`, { method: 'POST', body: JSON.stringify(body) });
      added++;
      setStatus(`Записываю… ${added + dup}/${items.length}`);
    }
    st.drafts = [];
    renderCards();
    renderWeek();
    setStatus(`Готово: добавлено ${added}` + (dup ? `, уже были ${dup}` : '') + (replaced ? `, прежнее расписание заменено (серий: ${replaced})` : '') + '.', 'ok');
  } catch (e) {
    setStatus('Ошибка записи: ' + e.message, 'err');
  } finally {
    setBusy(false);
  }
}

// ---------------- свободное время ----------------

async function showFree(from, to) {
  const tokP = ensureToken();
  if (to < from) [from, to] = [to, from];
  if (P.D.epoch(to) - P.D.epoch(from) > 31) to = P.D.add(from, 31);
  $('#freeFrom').value = from; $('#freeTo').value = to;
  $('#freeStatus').textContent = 'Смотрю календарь…';
  $('#freeStatus').className = 'status';
  try {
    await tokP;
    if (!st.calendars.length) await loadCalendars().catch(() => {});
    const a = P.D.parts(from), b = P.D.parts(P.D.add(to, 1));
    const ids = busyCalendarIds();
    const res = await gfetch('/freeBusy', {
      method: 'POST',
      body: JSON.stringify({ timeMin: new Date(a.y, a.m - 1, a.d).toISOString(), timeMax: new Date(b.y, b.m - 1, b.d).toISOString(), timeZone: TZ, items: ids.map((id) => ({ id })) }),
    });
    const busy = [];
    for (const id of ids) for (const x of res.calendars?.[id]?.busy || []) busy.push([Date.parse(x.start), Date.parse(x.end)]);
    const days = P.freeSlots(busy, from, to, S.dayStart, S.dayEnd, S.minFree);
    $('#freeResult').innerHTML = days.map(({ day, slots }) => `
      <div class="day"><h4>${P.WD_FULL[P.D.dow(day)]}, ${P.D.ru(day)}</h4>
      ${slots.length ? slots.map((s) => `<div class="slot"><b>${P.T.fmt(s.start)}–${s.end >= 1440 ? '24:00' : P.T.fmt(s.end)}</b><span>${P.fmtDuration(s.minutes)}</span></div>`).join('') : '<div class="none">Свободных окон нет</div>'}
      </div>`).join('');
    $('#freeStatus').textContent = `Окна от ${S.minFree} мин в рамках ${P.T.fmt(S.dayStart)}–${P.T.fmt(S.dayEnd)}, календарей учтено: ${ids.length}.`;
  } catch (e) {
    $('#freeStatus').textContent = 'Ошибка: ' + e.message;
    $('#freeStatus').className = 'status err';
  }
}

// ---------------- отрисовка ----------------

function setStatus(t, kind = '') { const el = $('#status'); el.textContent = t; el.className = 'status ' + kind; }
function setBusy(b) {
  st.busy = b;
  $('#recognizeBtn').disabled = b;
  $('#saveFab').disabled = b;
}
function showRaw(text) { $('#rawText').textContent = text || ''; $('#rawWrap').hidden = !text; }

function renderMode() {
  $$('.seg-btn').forEach((b) => b.classList.toggle('active', b.dataset.mode === st.mode));
  $('#modeHint').textContent = st.mode === 'schedule'
    ? 'Пары повторяются раз в 2 недели (чётная/нечётная) на все следующие месяцы. «2–16 нед.» задаёт конец, зачёты и экзамены — разово. Новое расписание заменит прежнее для той же недели (чёт/нечёт).'
    : 'События ставятся только на свою дату. Повтор (ежедневно, еженедельно, раз в 2 недели, ежемесячно, ежегодно) можно включить в карточке.';
}

function renderWeek() {
  const guessed = !!st.weekStart && st.drafts.some((d) => d.dayOffset != null);
  const sched = st.mode === 'schedule' && st.drafts.length > 0;
  $('#weekCard').hidden = !(guessed || sched);
  $('#weekGuess').hidden = !guessed;
  $('#weekNoWrap').hidden = !sched;
  if (st.weekStart) $('#weekStart').value = st.weekStart;
  $('#weekNo').value = st.weekNo ?? '';
}

function dayLabel(d) {
  if (!d.date) return 'Дата не указана';
  const r = d.repeat !== 'none' ? ' · ' + P.REPEATS[d.repeat].label.toLowerCase() + (d.until ? ' до ' + P.D.ru(d.until) : '') : '';
  return `${P.WD_FULL[P.D.dow(d.date)]}, ${P.D.ru(d.date)}${r}`;
}

function renderCards() {
  const box = $('#cards');
  box.innerHTML = '';
  const tpl = $('#cardTpl');
  for (const d of st.drafts) {
    const el = tpl.content.firstElementChild.cloneNode(true);
    el.dataset.id = d.id;
    el.classList.toggle('off', !d.checked);
    el.querySelector('.ev-check').checked = d.checked;
    el.querySelector('.ev-day').textContent = dayLabel(d);
    el.querySelector('.f-title').value = d.title;
    el.querySelector('.f-date').value = d.date || '';
    el.querySelector('.f-start').value = d.start;
    el.querySelector('.f-end').value = d.end;
    el.querySelector('.f-loc').value = d.location;
    el.querySelector('.f-repeat').value = d.repeat;
    el.querySelector('.f-until').value = d.until;
    el.querySelector('.f-until-wrap').hidden = d.repeat === 'none';
    el.querySelector('.ev-note').textContent = d.note;
    box.appendChild(el);
  }
  renderFab();
  renderWeek();
}

function renderFab() {
  const n = st.drafts.filter((d) => d.checked).length;
  const onAdd = !$('#tab-add').hidden;
  $('#saveFab').hidden = !(onAdd && st.drafts.length);
  $('#saveFab').textContent = (st.mode === 'schedule' ? 'Сохранить расписание' : 'Добавить в календарь') + ` (${n})`;
  $('#saveFab').disabled = st.busy || n === 0;
}

function updateDraft(id, patch) {
  const i = st.drafts.findIndex((d) => d.id === id);
  if (i < 0) return null;
  st.drafts[i] = { ...st.drafts[i], ...patch };
  return st.drafts[i];
}

// ---------------- картинка ----------------

function setImage(blob) {
  st.image = blob;
  const img = $('#preview');
  if (img.src) URL.revokeObjectURL(img.src);
  if (blob) img.src = URL.createObjectURL(blob);
  img.hidden = !blob;
  $('#dropEmpty').hidden = !!blob;
  $('#clearImg').hidden = !blob;
  if (blob) setStatus('Скрин загружен. Можно дописать текст и нажать «Распознать».');
}

async function takeShared() {
  if (!new URLSearchParams(location.search).has('shared') || !('caches' in window)) return;
  try {
    const c = await caches.open('sc-share');
    const img = await c.match('shared-image');
    const txt = await c.match('shared-text');
    if (img) setImage(await img.blob());
    if (txt) { const t = await txt.text(); if (t) $('#extra').value = t; }
    await c.delete('shared-image'); await c.delete('shared-text');
  } catch { /* */ }
  history.replaceState(null, '', location.pathname);
}

// ---------------- события интерфейса ----------------

function showTab(name) {
  $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  for (const n of ['add', 'free', 'settings']) $('#tab-' + n).hidden = n !== name;
  renderFab();
}

function bind() {
  $$('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('#accountBtn').addEventListener('click', () => { ensureToken().then(renderAccount).catch((e) => setStatus(e.message, 'err')); });
  $('#signOut').addEventListener('click', () => {
    if (st.token && window.google?.accounts?.oauth2) google.accounts.oauth2.revoke(st.token, () => {});
    st.token = null; st.email = ''; store.set('signed', false); store.set('email', '');
    st.calendars = []; renderCalendars(); renderAccount();
  });

  // режим
  $$('.seg-btn').forEach((b) => b.addEventListener('click', () => { st.mode = b.dataset.mode; renderMode(); replan(); }));

  // картинка
  const drop = $('#drop');
  drop.addEventListener('click', (e) => { if (e.target.id !== 'clearImg') $('#fileInput').click(); });
  drop.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') $('#fileInput').click(); });
  $('#fileInput').addEventListener('change', (e) => { const f = e.target.files?.[0]; if (f) setImage(f); e.target.value = ''; });
  $('#clearImg').addEventListener('click', (e) => { e.stopPropagation(); setImage(null); setStatus(''); });
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => {
    e.preventDefault(); drop.classList.remove('over');
    const f = [...(e.dataTransfer?.files || [])].find((x) => x.type.startsWith('image/'));
    if (f) setImage(f);
  });
  document.addEventListener('paste', (e) => {
    const f = [...(e.clipboardData?.files || [])].find((x) => x.type.startsWith('image/'));
    if (f) { setImage(f); showTab('add'); }
  });

  $('#recognizeBtn').addEventListener('click', recognize);
  $('#manualBtn').addEventListener('click', () => {
    const today = P.D.today();
    st.drafts.push(draftFrom({ title: '', date: today, start: '', end: '', repeat: st.mode === 'schedule' ? 'biweekly' : 'none' }));
    st.drafts[st.drafts.length - 1].checked = true;
    renderCards();
    const last = $('#cards').lastElementChild;
    last?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    last?.querySelector('.f-title')?.focus();
  });
  $('#useAI').addEventListener('change', (e) => store.set('useAI', e.target.checked));

  // неделя
  const setWeek = (ws) => { st.weekStart = P.D.monday(ws); replan(); };
  $('#weekStart').addEventListener('change', (e) => { if (e.target.value) setWeek(e.target.value); });
  $('#weekPrev').addEventListener('click', () => setWeek(P.D.add(st.weekStart, -7)));
  $('#weekNext').addEventListener('click', () => setWeek(P.D.add(st.weekStart, 7)));
  $('#weekNo').addEventListener('change', (e) => { const n = parseInt(e.target.value, 10); st.weekNo = n >= 1 && n <= 30 ? n : null; replan(); });

  // карточки (делегирование)
  const cards = $('#cards');
  cards.addEventListener('input', (e) => {
    const el = e.target.closest('.ev'); if (!el) return;
    const id = +el.dataset.id;
    const map = { 'f-title': 'title', 'f-loc': 'location' };
    for (const [cls, key] of Object.entries(map)) if (e.target.classList.contains(cls)) updateDraft(id, { [key]: e.target.value });
  });
  cards.addEventListener('change', (e) => {
    const el = e.target.closest('.ev'); if (!el) return;
    const id = +el.dataset.id;
    const t = e.target;
    let d = null;
    if (t.classList.contains('ev-check')) { d = updateDraft(id, { checked: t.checked }); el.classList.toggle('off', !t.checked); renderFab(); }
    else if (t.classList.contains('f-date')) d = updateDraft(id, { date: t.value, dayOffset: null });
    else if (t.classList.contains('f-start')) d = updateDraft(id, { start: t.value });
    else if (t.classList.contains('f-end')) d = updateDraft(id, { end: t.value });
    else if (t.classList.contains('f-until')) d = updateDraft(id, { until: t.value });
    else if (t.classList.contains('f-repeat')) {
      d = updateDraft(id, { repeat: t.value, until: t.value === 'none' ? '' : (st.drafts.find((x) => x.id === id)?.until || '') });
      el.querySelector('.f-until-wrap').hidden = t.value === 'none';
      if (t.value === 'none') el.querySelector('.f-until').value = '';
    }
    if (d) el.querySelector('.ev-day').textContent = dayLabel(d);
  });
  cards.addEventListener('click', (e) => {
    if (!e.target.classList.contains('ev-del')) return;
    const id = +e.target.closest('.ev').dataset.id;
    st.drafts = st.drafts.filter((d) => d.id !== id);
    renderCards();
  });

  $('#saveFab').addEventListener('click', save);

  // свободное время
  const today = () => P.D.today();
  $('#askBtn').addEventListener('click', () => {
    const ds = P.datesIn($('#question').value, today()).sort();
    if (!ds.length) { $('#freeStatus').textContent = 'Не нашёл дат в вопросе. Пример: «свободное время 20.10–23.10».'; $('#freeStatus').className = 'status err'; return; }
    showFree(ds[0], ds[ds.length - 1]);
  });
  $('#question').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#askBtn').click(); });
  $$('.quick').forEach((b) => b.addEventListener('click', () => {
    const t = today();
    if (b.dataset.quick === 'today') showFree(t, t);
    else if (b.dataset.quick === 'tomorrow') showFree(P.D.add(t, 1), P.D.add(t, 1));
    else showFree(t, P.D.add(t, 6));
  }));
  $('#freeShow').addEventListener('click', () => showFree($('#freeFrom').value || today(), $('#freeTo').value || $('#freeFrom').value || today()));

  // настройки
  $('#writeCal').addEventListener('change', (e) => store.set('writeCal', e.target.value));
  $('#busyCals').addEventListener('change', () => store.set('busyCals', $$('#busyCals input:checked').map((i) => i.value)));
  for (const [id, key] of [['dayStart', 'dayStart'], ['dayEnd', 'dayEnd'], ['minFree', 'minFree']]) {
    $('#' + id).value = store.get(key, $('#' + id).value);
    $('#' + id).addEventListener('change', (e) => { store.set(key, e.target.value); renderFreeHint(); });
  }
  $('#proxyUrl').value = store.get('proxy', '') || cfg.gigachatProxy || '';
  $('#proxyUrl').addEventListener('change', (e) => { store.set('proxy', e.target.value.trim()); renderAI(); });
  $('#appKey').value = S.appKey;
  $('#appKey').addEventListener('change', (e) => store.set('appKey', e.target.value));
  $('#clientId').value = store.get('clientId', '');
  $('#clientId').addEventListener('change', (e) => { store.set('clientId', e.target.value.trim()); renderAccount(); });
  $('#aiTest').addEventListener('click', async () => {
    $('#aiTestRes').textContent = 'проверяю…';
    try {
      const r = await aiRecognize(null, 'Тест: встреча завтра в 10:00');
      $('#aiTestRes').textContent = `работает, событий: ${(r.events || []).length}`;
    } catch (e) { $('#aiTestRes').textContent = 'ошибка: ' + e.message; }
  });

  // установка
  let deferred = null;
  window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e; $('#installBtn').hidden = false; });
  $('#installBtn').addEventListener('click', async () => { if (!deferred) return; deferred.prompt(); await deferred.userChoice; deferred = null; $('#installBtn').hidden = true; });
  if (/iphone|ipad|ipod/i.test(navigator.userAgent) && !navigator.standalone) $('#iosHint').hidden = false;
}

function renderAI() {
  const has = !!S.proxy;
  $('#useAI').checked = has && store.get('useAI', true);
  $('#useAI').disabled = !has;
  $('#aiState').textContent = has ? '' : '— не настроена (Настройки)';
}
function renderFreeHint() {
  $('#freeHint').textContent = `Свободным считается окно от ${S.minFree} мин в рамках ${P.T.fmt(S.dayStart)}–${P.T.fmt(S.dayEnd)}. Учитываются все повторы из Google Календаря.`;
}

// ---------------- старт ----------------

bind();
renderMode();
renderAccount();
renderAI();
renderFreeHint();
renderCards();
const t0 = P.D.today();
$('#freeFrom').value = t0; $('#freeTo').value = t0;
takeShared();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
