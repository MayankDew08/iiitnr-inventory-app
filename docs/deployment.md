# Deployment

The production deployment runs on the IIITNR server behind Caddy, with the
backend and PostgreSQL in a Podman Compose stack.

## Topology

```
Internet
   │
   ▼
┌──────────────┐  :80/:443  ┌──────────────────────────────────────┐
│    Caddy     │────────────▶│ backend (Fastify on Bun, port 4000)  │
│ TLS + static │              │           │                          │
└──────────────┘              │           ▼                          │
                              │   ┌──────────────────┐                │
                              │   │  PostgreSQL      │                │
                              │   └──────────────────┘                │
                              └──────────────────────────────────────┘
```

- **Caddy** terminates TLS and serves the static web client from `/srv/web`.
- **backend** is the Fastify API, exposed only on the internal Docker network.
- **db** is PostgreSQL, reachable only from the backend container.
- The Compose stack lives in `backend/compose.prod.yaml`.

## Configuration

`backend/.env` is loaded into the container via `env_file`. The variables that
matter in production are listed in `backend/.env.example`; at minimum:

| Variable | Notes |
|---|---|
| `DATABASE_URL` **or** `DB_USER` + `DB_PASSWORD` | The Compose file builds `DATABASE_URL=postgres://${DB_USER}:${DB_PASSWORD}@db:5432/iiitnr_inventory` from the credentials, and uses them to initialise the database container |
| `JWT_SECRET` | Signs auth tokens — must not be the placeholder value |
| `GOOGLE_CLIENT_ID` | Google sign-in (without it that route returns 500) |
| `SMTP_*` | Optional — only needed for the expiry / late-fee emails, see [troubleshooting](troubleshooting.md#email) |
| `MAIL_ENABLED` | Set to `true` to actually send. Until then the mail sweeps only log what they would send |
| `ALLOWED_ORIGINS` | CORS allow-list for the web client |
| `UPI_VPA` + `UPI_PAYEE_NAME` | Institute UPI VPA and payee name handed to the app for fine payments — required at boot |

## Deploying

```bash
just deploy        # git pull + deploy-web + rebuild the backend container
just deploy-web    # build the web client and publish it to /srv/web
```

`just deploy` does three things:

1. `git pull` — picks up the latest commit on `main`
2. `just deploy-web` — builds the Kotlin/Wasm web client and copies it to `/srv/web`
   (requires `GOOGLE_WEB_CLIENT_ID` in `backend/.env`)
3. `podman compose -f backend/compose.prod.yaml up --build -d --force-recreate backend`
   — rebuilds and recreates the backend container

The database container is **not** recreated, so data is preserved. Migrations run
automatically on container start (`bun run migrate`).

## TLS certificates

Caddy reads its certificates from `backend/certs/` (gitignored). Caddy's own
`Caddyfile` handles the `inventory.iiitnr.ac.in` domain and serves
`/srv/web` for static assets.

## File storage

User-uploaded files live on a **persistent volume**, the same way `uploads/`
(component images) does:

- `uploads/` — publicly served component images.
- `private-uploads/payments/` — payment proof screenshots. The public file
  server does **not** serve this folder; proofs are only returned by the
  authenticated `GET /payments/:id/proof` route (owner, LA or admin, reached
  as `/api/payments/:id/proof` through Caddy in production), so
  no public URL ever exposes them.

Both folders must be on persistent storage in the deployment; `compose.prod.yaml`
mounts `./uploads:/app/uploads` and `./private-uploads:/app/private-uploads`.
The container runs as uid `65532`, so create both host directories up front and
hand them to that uid — otherwise the mkdir at boot and the proof upload fail:

```bash
mkdir -p uploads private-uploads/payments
sudo chown -R 65532:65532 uploads private-uploads
```

The entrypoint probes `private-uploads/` at boot and refuses to start when it is
not writable, so a mis-owned mount fails at deploy time rather than after a
student has paid. Proofs written before the move to `private-uploads/` (rows holding a path
instead of a bare file name) are repaired once with
`bun run scripts/move-payment-proofs.ts`, then the old files are deleted by hand.

## Releases

```bash
just release 1.2.3   # pushes to main and creates a GitHub release with generated notes
```

Releases trigger the GitHub Actions release workflow, which publishes the
Android, Windows and Linux builds — the same artefacts linked from the
"Download App" section of the README.

## Rollback

```bash
git revert <sha> && just deploy
```

Because the database is not recreated on deploy, rollbacks only affect the
application code. Schema changes are additive migrations; a revert that touches
the schema needs a matching migration.
