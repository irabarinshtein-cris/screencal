// СкринКалендарь — прокси GigaChat для Yandex Cloud Functions (среда Node.js 18+).
// Хранит ключ GigaChat у себя, принимает скрин/текст от приложения и возвращает события JSON.
//
// Переменные окружения функции:
//   GIGACHAT_AUTH_KEY  — «Ключ авторизации» из личного кабинета GigaChat API (обязательно)
//   APP_PASSWORD       — пароль приложения (рекомендуется: без него функцией сможет пользоваться любой, кто знает адрес)
//   GIGACHAT_SCOPE     — GIGACHAT_API_PERS (физлица, по умолчанию)
//   GIGACHAT_MODEL     — модель, по умолчанию GigaChat-2-Max
//   GIGACHAT_CA_PEM    — корневой сертификат Минцифры в формате PEM (если не задан, проверка сертификата Сбера отключается)
//   ALLOWED_ORIGIN     — адрес сайта приложения, например https://login.github.io (по умолчанию *)

const https = require('node:https');
const tls = require('node:tls');
const crypto = require('node:crypto');

const AUTH_URL = process.env.GIGACHAT_AUTH_URL || 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth';
const BASE = (process.env.GIGACHAT_BASE || 'https://gigachat.devices.sberbank.ru/api/v1').replace(/\/$/, '');
const SCOPE = process.env.GIGACHAT_SCOPE || 'GIGACHAT_API_PERS';
const MODEL = process.env.GIGACHAT_MODEL || 'GigaChat-2-Max';
const ORIGIN = process.env.ALLOWED_ORIGIN || '*';

const agent = new https.Agent(
  process.env.GIGACHAT_CA_PEM
    ? { ca: [...tls.rootCertificates, process.env.GIGACHAT_CA_PEM.replace(/\\n/g, '\n')], keepAlive: true }
    : { rejectUnauthorized: false, keepAlive: true },
);

function request(url, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method, headers, agent, timeout: 110000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => req.destroy(new Error('таймаут запроса к GigaChat')));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

let token = null;
let tokenExp = 0;

async function getToken() {
  if (token && Date.now() < tokenExp - 60000) return token;
  if (!process.env.GIGACHAT_AUTH_KEY) throw new Error('в функции не задан GIGACHAT_AUTH_KEY');
  const r = await request(AUTH_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
      RqUID: crypto.randomUUID(),
      Authorization: 'Basic ' + process.env.GIGACHAT_AUTH_KEY.replace(/^Basic\s+/i, ''),
    },
    body: 'scope=' + SCOPE,
  });
  if (r.status !== 200) throw new Error(`авторизация GigaChat: ${r.status} ${r.text.slice(0, 200)}`);
  const j = JSON.parse(r.text);
  token = j.access_token;
  tokenExp = j.expires_at || Date.now() + 25 * 60000;
  return token;
}

async function uploadImage(buf) {
  const boundary = '----screencal' + crypto.randomBytes(8).toString('hex');
  const body = Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="screen.jpg"\r\nContent-Type: image/jpeg\r\n\r\n`),
    buf,
    Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\ngeneral\r\n--${boundary}--\r\n`),
  ]);
  const r = await request(BASE + '/files', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + (await getToken()), 'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Content-Length': body.length, Accept: 'application/json' },
    body,
  });
  if (r.status !== 200) throw new Error(`загрузка картинки: ${r.status} ${r.text.slice(0, 200)}`);
  return JSON.parse(r.text).id;
}

async function chat(messages) {
  const body = JSON.stringify({ model: MODEL, messages, temperature: 0.1, max_tokens: 6000 });
  const r = await request(BASE + '/chat/completions', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + (await getToken()), 'Content-Type': 'application/json', Accept: 'application/json', 'Content-Length': Buffer.byteLength(body) },
    body,
  });
  if (r.status !== 200) throw new Error(`GigaChat: ${r.status} ${r.text.slice(0, 300)}`);
  return JSON.parse(r.text).choices?.[0]?.message?.content || '';
}

const WD = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];

function prompt({ today, tz, weekStart, text, hasImage }) {
  const wd = WD[new Date(today + 'T12:00:00Z').getUTCDay()];
  return [
    `Сегодня ${today} (${wd}). Часовой пояс ${tz || 'Europe/Moscow'}.`,
    hasImage ? 'На изображении — расписание или план.' : 'Изображения нет, события описаны текстом ниже.',
    text ? `Указания пользователя (они важнее всего остального): «${text}»` : '',
    'Верни ТОЛЬКО JSON-объект без пояснений и без markdown:',
    '{"kind":"schedule или once","weekNumber":число или null,"events":[{"date":"YYYY-MM-DD","start":"HH:MM","end":"HH:MM или null","title":"…","location":"…","note":"…","repeat":"none|daily|weekly|biweekly|monthly|yearly","until":"YYYY-MM-DD или null"}]}',
    'Правила:',
    '- kind = "schedule", если это учебное расписание пар (таблица по дням недели), иначе "once".',
    `- Если в таблице нет дат, первая колонка — понедельник ${weekStart}, следующие колонки — следующие дни недели.`,
    '- weekNumber — номер учебной недели, если он указан (например «№8, чётная» → 8).',
    '- Пары в расписании повторяются раз в 2 недели: repeat = "biweekly".',
    '- Пометка вида «(2–16 нед.)» при текущей неделе N: until = дата пары + (16 − N) × 7 дней. «(на 8 нед.)» — один раз: repeat = "none".',
    '- Зачёты, экзамены, комиссии, пересдачи, консультации — repeat = "none".',
    '- Пустые ячейки «--» и строку состояния телефона (часы, батарея) пропускай.',
    '- title: предмет или событие и тип занятия, например «УиАИС — Лекция»; location: аудитория или кабинет; note: преподаватель и прочее.',
    '- Если указания пользователя задают повтор или срок («каждую неделю до 20 декабря») — примени их ко всем событиям.',
    '- Если год не указан, бери ближайший к сегодняшней дате. Если указан только день недели — ближайший такой день начиная с сегодня.',
  ].filter(Boolean).join('\n');
}

const REPEATS = new Set(['none', 'daily', 'weekly', 'biweekly', 'monthly', 'yearly']);
const hhmm = (s) => (typeof s === 'string' && /^\d{1,2}[:.]\d{2}$/.test(s.trim()) ? s.trim().replace('.', ':').padStart(5, '0') : null);
const ymd = (s) => (typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s.trim()) ? s.trim() : null);

function parseAnswer(content) {
  const s = content.replace(/```(?:json)?/g, '');
  let j = null;
  const a = s.indexOf('{'), b = s.lastIndexOf('}');
  if (a >= 0 && b > a) { try { j = JSON.parse(s.slice(a, b + 1)); } catch { j = null; } }
  if (!j || !Array.isArray(j.events)) {
    const x = s.indexOf('['), y = s.lastIndexOf(']');
    if (x >= 0 && y > x) { try { j = { events: JSON.parse(s.slice(x, y + 1)) }; } catch { /* */ } }
  }
  if (!j) return { kind: 'once', weekNumber: null, events: [] };
  const events = (j.events || []).map((e) => ({
    date: ymd(e.date),
    start: hhmm(e.start),
    end: hhmm(e.end),
    title: String(e.title || '').trim() || 'Событие',
    location: e.location && e.location !== 'null' ? String(e.location) : '',
    note: e.note && e.note !== 'null' ? String(e.note) : '',
    repeat: REPEATS.has(e.repeat) ? e.repeat : 'none',
    until: ymd(e.until),
  })).filter((e) => e.date && e.start);
  return { kind: j.kind === 'schedule' ? 'schedule' : 'once', weekNumber: Number.isInteger(j.weekNumber) ? j.weekNumber : null, events };
}

function reply(statusCode, obj) {
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': ORIGIN,
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-App-Key',
      'Access-Control-Max-Age': '86400',
    },
    body: obj == null ? '' : JSON.stringify(obj),
  };
}

module.exports.handler = async function (event) {
  if (event.httpMethod === 'OPTIONS') return reply(204, null);
  if (event.httpMethod !== 'POST') return reply(405, { error: 'нужен POST' });
  const h = Object.fromEntries(Object.entries(event.headers || {}).map(([k, v]) => [k.toLowerCase(), v]));
  if (process.env.APP_PASSWORD && h['x-app-key'] !== process.env.APP_PASSWORD) return reply(401, { error: 'неверный пароль приложения' });
  try {
    const raw = event.isBase64Encoded ? Buffer.from(event.body || '', 'base64').toString('utf8') : (event.body || '');
    const req = JSON.parse(raw || '{}');
    const today = ymd(req.today) || new Date().toISOString().slice(0, 10);
    const weekStart = ymd(req.weekStart) || today;
    const text = String(req.text || '').slice(0, 2000);
    const message = { role: 'user', content: prompt({ today, tz: req.tz, weekStart, text, hasImage: !!req.image }) };
    if (req.image) message.attachments = [await uploadImage(Buffer.from(req.image, 'base64'))];
    if (!req.image && !text) return reply(400, { error: 'нет ни картинки, ни текста' });
    const content = await chat([
      { role: 'system', content: 'Ты помощник, который переносит расписания и планы в календарь. Отвечаешь строго JSON.' },
      message,
    ]);
    return reply(200, { ...parseAnswer(content), raw: content });
  } catch (e) {
    return reply(502, { error: e.message || String(e) });
  }
};

// для локальной проверки разбора ответа
module.exports._parseAnswer = parseAnswer;
