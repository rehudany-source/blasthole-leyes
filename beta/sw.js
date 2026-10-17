// BlastHole Leyes · service worker (funciona sin internet)
const CANAL = 'beta';
const PREF = CANAL === 'beta' ? 'bhl-beta-' : 'bhl-v-';
const CACHE = PREF + '64-1792280410';
const FONTS = 'bhl-fuentes';
const BASE = ['./', 'index.html', 'manifest.webmanifest', 'apple-touch-icon.png', 'icon-192.png', 'icon-512.png', 'favicon-32.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(BASE.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  // solo borra cachés viejas de su propio canal (la versión normal no toca la de pruebas y viceversa)
  const mia = k => k.startsWith(PREF) || (CANAL !== 'beta' && /^bhl-\d/.test(k));
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE && k !== FONTS && mia(k)).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Supabase (sincronización) siempre va directo a la red
  if (url.hostname.endsWith('supabase.co')) return;
  // Fuentes: de la caché si ya están, si no de la red
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open(FONTS).then(async c => {
      const hit = await c.match(req);
      if (hit) return hit;
      try { const r = await fetch(req); if (r.ok || r.type === 'opaque') c.put(req, r.clone()); return r; } catch (err) { return Response.error(); }
    }));
    return;
  }
  if (url.origin !== location.origin) return;
  // la versión normal no atiende la carpeta de pruebas (/beta/), que tiene su propio service worker
  if (CANAL !== 'beta' && /\/beta\//.test(url.pathname)) return;
  // La página: primero la red (para recibir actualizaciones), sin internet la copia guardada
  if (req.mode === 'navigate') {
    e.respondWith((async () => {
      const c = await caches.open(CACHE);
      try {
        const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 4000);
        const r = await fetch(req, { signal: ctl.signal, cache: 'no-cache' }); clearTimeout(t);
        if (r.ok) c.put('index.html', r.clone());
        return r;
      } catch (err) {
        return (await c.match('index.html')) || (await c.match('./')) || Response.error();
      }
    })());
    return;
  }
  e.respondWith(caches.open(CACHE).then(c => c.match(req)).then(hit => hit || fetch(req).then(r => {
    if (r.ok) { const cp = r.clone(); caches.open(CACHE).then(c => c.put(req, cp)); }
    return r;
  })));
});
