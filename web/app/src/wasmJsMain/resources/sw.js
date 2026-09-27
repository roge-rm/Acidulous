// Acidulous's service worker. Two jobs, because a page has only one:
//
// **Isolation.** The engine shares its memory with the audio thread, and a
// browser allows that only on a cross-origin isolated page - one served with
// COOP and COEP headers, which a static host like GitLab Pages cannot send.
// Every answer given here carries them.
//
// **The app, kept.** Installed or not, the page opens from what is kept here,
// with no connection and without downloading twenty megabytes again. Every
// file of a build is kept together, under the build's fingerprint, because
// three of them (the engine, its loader and the app's script) keep their names
// from one build to the next and must never be mixed across two. The build
// writes the fingerprint and the list into the two lines below
// (web/app/build.gradle.kts).
//
// A new build is noticed when the app is opened and the browser finds this
// file changed. It is fetched whole in the background while the old one
// carries on, and takes over when it is all here: the next time the app is
// opened, it is the new build. (Chrome does not get round to checking again
// while the app is running - its update checks wait until the page has gone -
// so a build published mid-session arrives two openings later, not one.) The
// first visit keeps what the page itself fetches, and then the rest (fill), so
// nothing is downloaded twice.

const VERSION = '__VERSION__';
const FILES = __FILES__;
const CACHE = 'acidulous-' + VERSION;
const built = !VERSION.startsWith('__');

self.addEventListener('install', (event) => {
  self.skipWaiting();
  // An update: all of it, before it replaces the build that is working.
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

// The page, once the app is running: keep whatever it has not asked for yet.
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
  // The sizes for the progress bar are only worth the network's: a kept build
  // needs no bar. Everything else kept is answered from here.
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
