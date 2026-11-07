const CACHE_NAME = "streamarena-shell-v2";
const ROOT_URL = new URL("./", self.location.href).href;
const OFFLINE_URL = new URL("./offline.html", self.location.href).href;

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.add(new Request(OFFLINE_URL, { cache: "reload" })))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(key => key.startsWith("streamarena-shell-") && key !== CACHE_NAME).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET" || request.mode !== "navigate") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  const rootPath = new URL(ROOT_URL).pathname;
  // Never cache query-bearing navigations: OAuth/auth callback codes and other
  // one-time parameters must not be persisted in Cache Storage.
  const isCacheableRoot = url.pathname === rootPath && !url.search;

  event.respondWith((async () => {
    try {
      const response = await fetch(request);
      const responseUrl = new URL(response.url || request.url);
      if (
        isCacheableRoot &&
        response.ok &&
        response.type === "basic" &&
        responseUrl.origin === self.location.origin &&
        responseUrl.pathname === rootPath &&
        !responseUrl.search
      ) {
        event.waitUntil(
          caches.open(CACHE_NAME).then(cache => cache.put(ROOT_URL, response.clone()))
        );
      }
      return response;
    } catch {
      if (isCacheableRoot) {
        const cachedRoot = await caches.match(ROOT_URL);
        if (cachedRoot) return cachedRoot;
      }
      const offlinePage = await caches.match(OFFLINE_URL);
      if (offlinePage) return offlinePage;
      return new Response("StreamArena is offline.", {
        status: 503,
        headers: { "Content-Type": "text/plain; charset=utf-8" }
      });
    }
  })());
});
