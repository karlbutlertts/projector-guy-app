const CACHE_NAME = 'xbj-remote-v1';
const ASSETS_TO_CACHE = [
  './remote.html',
  './manifest-remote.json'
];

// Install: cache assets
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(ASSETS_TO_CACHE).catch(err => {
        // Fail silently if any asset can't be cached (network issue during install)
        console.warn('Failed to cache some assets:', err);
      });
    })
  );
  self.skipWaiting();
});

// Activate: clean up old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames.map(cacheName => {
          if (cacheName !== CACHE_NAME) {
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
  self.clients.claim();
});

// Fetch: network-first for commands, cache-first for UI
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);

  // Skip non-GET requests
  if (event.request.method !== 'GET') {
    return;
  }

  // For API/command requests: always try network first
  if (url.pathname.includes('/command')) {
    event.respondWith(
      fetch(event.request)
        .catch(() => {
          // Network failed, but we're offline so just resolve with no response
          // The app will handle the error gracefully
          return new Response(null, { status: 0 });
        })
    );
    return;
  }

  // For UI assets: cache-first with network fallback
  event.respondWith(
    caches.match(event.request)
      .then(response => {
        if (response) {
          return response;
        }
        return fetch(event.request).then(response => {
          // Cache successful responses
          if (response.ok) {
            const cache = caches.open(CACHE_NAME);
            cache.then(c => c.put(event.request, response.clone()));
          }
          return response;
        });
      })
      .catch(() => {
        // Network error and not in cache — return cached version if available
        return caches.match('./remote.html');
      })
  );
});
