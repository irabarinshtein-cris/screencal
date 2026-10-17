// СкринКалендарь — сервис-воркер: работа без сети и приём скринов через «Поделиться».
const CACHE = 'sc-v1';
const SHELL = ['./', 'index.html', 'app.js', 'parser.js', 'style.css', 'config.js', 'manifest.webmanifest', 'icons/icon.svg', 'icons/icon-192.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE && k !== 'sc-share').map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  // скрин из «Поделиться» (Android, установленное приложение)
  if (e.request.method === 'POST' && url.pathname.endsWith('/share-target')) {
    e.respondWith((async () => {
      const fd = await e.request.formData();
      const file = fd.get('image');
      const text = [fd.get('title'), fd.get('text')].filter(Boolean).join(' ');
      const c = await caches.open('sc-share');
      if (file) await c.put('shared-image', new Response(file));
      await c.put('shared-text', new Response(text || ''));
      return Response.redirect('./?shared=1', 303);
    })());
    return;
  }
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  const heavy = url.pathname.includes('/vendor/') || url.pathname.includes('/tessdata/');
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    if (heavy) { // большие файлы — сначала из кэша
      const hit = await cache.match(e.request);
      if (hit) return hit;
      const r = await fetch(e.request);
      if (r.ok) cache.put(e.request, r.clone());
      return r;
    }
    try { // приложение — сначала из сети, чтобы обновления приходили сразу
      const r = await fetch(e.request);
      if (r.ok) cache.put(e.request, r.clone());
      return r;
    } catch {
      return (await cache.match(e.request)) || (await cache.match('index.html'));
    }
  })());
});
