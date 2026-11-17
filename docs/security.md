# Auth token storage

The JWT issued by `POST /auth/login` and `POST /auth/google` is a 7-day bearer
token (`expiresIn: '7d'` in `backend/src/app.ts`). The backend has no revocation
list, so a leaked token is valid until it expires — which is what makes *where
each client stores it* the security question, not the token format.

## Per-platform storage

| Platform | Storage | Readable by | Status |
|---|---|---|---|
| Android | AES-256/GCM under a non-exportable `AndroidKeyStore` key, `datastore/` excluded from backup and device transfer | The app only | Encrypted, not backed up |
| iOS | `NSUserDefaults` (plaintext plist inside the app container) | The app only (iOS app sandbox) | Acceptable for now, not keychain-backed |
| Web | **Memory only**, backed by an httpOnly `SameSite=Strict` session cookie | Not readable from JavaScript | See below |
| Desktop | `java.util.prefs` (plaintext XML/plist) | Any process running as the same user | **Accepted risk**, see below |

## Web: cookie session, token in memory

`localStorage` is readable by any script on the origin, so a single XSS would
have handed over a 7-day credential. The web build therefore keeps the token in
memory for the lifetime of the tab and never writes it to storage:

1. `POST /auth/login` returns the token in the body **and** sets an httpOnly,
   `SameSite=Strict`, `Secure` (in production) `iiitnr_session` cookie.
2. The token lives in `WasmTokenManager`'s in-memory `StateFlow`.
3. On reload the tab calls `POST /auth/session`, which `jwtVerify`s the cookie
   and mints a fresh token — so a reload stays signed in without a token in
   JavaScript-reachable storage.
4. Logout calls `POST /auth/logout` (clears the cookie) before dropping the
   in-memory token, so a reload cannot silently restore a session the user ended.

A token left in `localStorage` by a pre-cookie build is deleted on first load
and never read, so existing web users get one forced re-login — the same trade
the Android Keystore migration made, chosen over a one-off exchange path that
would go dead within the token's 7-day life.

The cookie is `SameSite=Strict`, so other sites never carry it. That is not the
whole CSRF story, though: `Strict` compares *sites*, not origins, so another host
under `*.iiitnr.ac.in` could still POST with the cookie attached. A mutating
request that authenticated by cookie rather than by header must therefore also
come from an origin on the CORS allow-list (`setupCookieOriginGuard` in
`app.ts`), otherwise it gets a 403. Bearer requests and requests with no `Origin`
header — native and CLI clients — are exempt, which is what keeps mobile apps
working. With no `ALLOWED_ORIGINS` set (development, tests) the guard allows any
origin, matching what CORS already does there.

Native clients are untouched: they keep sending `Authorization: Bearer`, and
`@fastify/jwt` prefers the header when both are present.

In development the wasm client and the API run on different ports, so the Ktor JS
engine opts into `credentials: 'include'` and the API answers CORS with
`credentials: true`. In production Caddy serves `/srv/web` and the API from the
same origin, where none of that is needed.

## Desktop: accepted risk

`DesktopTokenManager` uses `java.util.prefs`, a plaintext XML document on
Linux/Windows and a plist on macOS. Any process running as the logged-in user can
read it.

This is accepted rather than fixed because the only real fix is an OS keychain,
which costs a JNA-backed dependency (`libsecret` on Linux, Keychain on macOS,
Credential Manager on Windows) plus a fallback path when no secret service is
running — and the fallback silently returns to the same plaintext file. On a
single-user lab machine, where the same user can also read the app's own
`uploads/` and cache files, the marginal gain does not cover the packaging risk.

Mitigations that do apply today: the token is per-user, expires in 7 days, and
the app never logs it. If the desktop build is ever distributed to shared
machines, revisit this and wire up a keychain.

On iOS the token sits in `NSUserDefaults`, a plaintext plist inside the app
container. The iOS app sandbox keeps it away from other apps, so it is a lower
risk than desktop and is left as is; moving it to the Keychain
(`kSecClassGenericPassword`) is the follow-up if that changes.
