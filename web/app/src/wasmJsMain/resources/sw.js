// Acidulous's service worker. It does two things:
//
// Isolation. The engine shares memory with the audio thread, which browsers
// only allow on a cross-origin isolated page (COOP and COEP headers). Static
// hosts like GitLab Pages can't send those, so every response here adds them.
//
// Offline. The app opens from the cache, with no connection and without
// downloading twenty megabytes again. All files of a build are cached
// together under the build's fingerprint, because the engine, its loader and
// the app's script keep the same names between builds and must never be
// mixed. The build writes the fingerprint and file list into the two lines
// below (web/app/build.gradle.kts).
//
// A new build is found when the app is opened and the browser sees this file
// changed. It downloads in the background and takes over the next time the
// app is opened. Chrome doesn't check again while the app is running, so a
// build published mid-session shows up two openings later. On the first visit
// the page's own requests are cached, then fill() gets the rest, so nothing
// is downloaded twice.

const VERSION = '__VERSION__';
const FILES = __FILES__;
const CACHE = 'acidulous-' + VERSION;
const built = !VERSION.startsWith('__');

self.addEventListener('install', (event) => {
  self.skipWaiting();
  // An update: fetch all of it before it replaces the working build.
  if (built && self.registration.active) event.waitUntil(fill());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith('acidulous-') && name !== CACHE) await caches.delete(name);
    }
    await self.clients.claim();
  })());
});

// Once the app is running the page asks for the rest of the files to be cached.
self.addEventListener('message', (event) => {
  if (event.data === 'fill' && built) event.waitUntil(fill());
});

async function fill() {
  const cache = await caches.open(CACHE);
  for (const file of FILES) {
    if (await cache.match(file)) continue;
    try {
      const response = await fetch(file, { cache: 'no-cache' });
      if (response.ok) await cache.put(file, response);
    } catch (e) {
      return; // offline: the rest another time
    }
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== location.origin) return;
  event.respondWith(answer(request, url));
});

async function answer(request, url) {
  let file = url.pathname.slice(new URL(self.registration.scope).pathname.length);
  if (request.mode === 'navigate' || file === '') file = 'index.html';
  const kept = built && FILES.includes(file);
  // load-sizes.json is only for the progress bar, which a cached build doesn't
  // need, so it always comes from the network. Everything else cached is
  // answered from here.
  if (kept && file !== 'load-sizes.json') {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(file);
    if (hit) return isolated(hit);
    const response = await fetch(request);
    if (response.ok) await cache.put(file, response.clone());
    return isolated(response);
  }
  try {
    return isolated(await fetch(request));
  } catch (e) {
    const hit = kept ? await caches.match(file) : undefined;
    if (hit) return isolated(hit);
    throw e;
  }
}

function isolated(response) {
  if (response.status === 0) return response;
  const headers = new Headers(response.headers);
  headers.set('Cross-Origin-Embedder-Policy', 'require-corp');
  headers.set('Cross-Origin-Opener-Policy', 'same-origin');
  headers.set('Cross-Origin-Resource-Policy', 'same-origin');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}
