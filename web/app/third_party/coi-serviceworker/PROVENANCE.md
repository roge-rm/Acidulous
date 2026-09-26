# coi-serviceworker 0.1.7, vendored

The engine shares its memory with the audio worklet, and a browser only
allows shared memory on a page that is cross-origin isolated - served with
`Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy` headers. A
static host like GitLab Pages cannot set headers, so this service worker
adds them: the first visit registers it and reloads, and every visit after
is isolated. Where the server sends the headers itself (web/engine/test/
serve.py), it does nothing.

    https://registry.npmjs.org/coi-serviceworker/-/coi-serviceworker-0.1.7.tgz
    sha512-bjSUqEngCPOkErY2vbyWsaIGCNRODYzlNycaREVw5s12/C8SM+RnRUUeX6pZbTtov6C52ZLY/+tvHK+BDxuUuA==

`coi-serviceworker.js` is the package's own, unchanged, in
`src/wasmJsMain/resources/` so it is served beside the page; `LICENSE` (MIT)
is here. The package's README, demo page and minified copy are left behind.
