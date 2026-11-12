# Troubleshooting

## Email

### Nothing is being sent

**Outgoing mail is opt-in.** The backend only really sends when
`MAIL_ENABLED=true` is set in `backend/.env`; without it the mail sweeps log
what they *would* have sent (`[mail disabled] would send …`) and nothing leaves
the machine. This is deliberate — the sweeps run wherever the backend runs, so
without the switch a developer machine with a real mailbox configured would mail
real people.

The backend logs which mode it is in at startup:

```
Outgoing mail is disabled (MAIL_ENABLED is not "true")
```

To turn it on for a real environment:

```env
MAIL_ENABLED=true
```

### `535-5.7.8 Username and Password not accepted`

`iiitnr.ac.in` is hosted on Google Workspace, and Workspace no longer accepts
password-based ("less secure app") SMTP auth — the **account** password cannot
authenticate over SMTP, no matter how correct it is.

Use an **app password** instead:
<https://myaccount.google.com/apppasswords> (2-Step Verification must be on).
The generated code is 16 characters — **remove the spaces** before pasting it
into `SMTP_PASS`.

### No email is being sent, and nothing is logged

`sendMail()` returns `false` instead of throwing, so a misconfigured mail setup
is silent. Check, in order:

1. `MAIL_ENABLED=true` is set in `backend/.env`
2. `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM` are all set
3. The backend was **restarted** after the env change — the transporter is built
   once at startup

To try a send by hand, run the mail service directly:

```bash
cd backend && bun -e '
import "dotenv/config";
const { sendMail } = await import("./src/services/MailService.js");
const ok = await sendMail("you@example.com", "Test", "Hello from the inventory system");
console.log(ok ? "sent" : "failed");
'
```

### `address is not a deliverable address`

The address in the user row is not a valid email — the backend skips it instead
of failing the whole sweep. Fix the address in the admin user panel.

### Emails land in spam

Workspace signs outgoing mail for the domain, but DKIM and DMARC have to be
enabled by a Workspace administrator. Until then, the expiry and fee notices
may not reach students. The records live in the Admin console under *Apps →
Email → Gmail settings*.

## Tests

### The suite fails when run in parallel

The test files share one database, so they have to run **one after another**:

```bash
bun test --parallel=1   # what `just test` and CI use
```

Running plain `bun test` executes the files concurrently against the same
database, and one file's truncate can wipe another file's data mid-test. If you
see unrelated failures, re-run with `--parallel=1`.

## Backend

### `JWT_SECRET is required and must not be "change-me"`

The app refuses to start without a real secret:

```bash
openssl rand -hex 32
```

### `DATABASE_URL is required`

Set `DATABASE_URL` in `backend/.env`, **or** set `DB_USER` and `DB_PASSWORD` —
the Compose files build the connection string from the latter and use them to
initialise the database container. Not both.

### `Google OAuth not configured` (HTTP 500 on sign-in)

`GOOGLE_CLIENT_ID` is missing from `backend/.env`. Email/password sign-in
still works; only Google sign-in is disabled.

### `bun run lint` fails with "typescript-eslint does not support TS 7.0"

A known incompatibility between the installed TypeScript version and
`typescript-eslint`. It is unrelated to your changes — the same failure happens
on a clean checkout. Fix it by upgrading `typescript-eslint` to a version that
supports the installed TypeScript, or by pinning TypeScript to a supported
major.

### Requests are not expiring

Expiry is driven by an hourly background sweep inside the backend process
(`backend/src/services/request-expiry.ts`). If the backend is not running, no
sweep happens and overdue requests stay open. The same applies to the 7-day
renewal reminder email.

## Client

### The Android emulator cannot reach a local backend

The Android **debug** build points at the hosted backend
(`https://iiitnr-inventory-backend.onrender.com`, see
`app/shared/src/androidMain/.../DevBaseUrl.android.kt`). The desktop and web
debug builds point at `http://localhost:4000`.

To use a locally running backend from an Android emulator, change
`devBaseUrl()` to `http://10.0.2.2:4000` — `10.0.2.2` is how the emulator
reaches the host machine's `localhost`. A physical device needs the host's LAN
IP instead.

### The app shows an update banner

The client calls `GET /version` on startup and compares the server version with
its own. The banner appears when the server is older than the installed client
— redeploy the backend (`just deploy`) to bring them back in sync.
