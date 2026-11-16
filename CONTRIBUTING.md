# Contributing

Thanks for taking the time to contribute. This document covers the things that
are easy to get wrong: which checks must pass, how to run them locally, and how
changes land on `main`.

If you only read one section, read [Before you commit](#before-you-commit).

---

## What is in here

| Path | What it is |
|---|---|
| `backend/` | Fastify + Bun + PostgreSQL API, Drizzle schema, `bun test` suites |
| `app/` | Kotlin Multiplatform client |
| `app/shared/` | Shared Compose UI + data layer (the actual app lives here) |
| `app/android/`, `app/desktop/`, `app/web/`, `app/iosApp/` | Platform runners |
| `docs/` | Long-form docs — `schema.md` is **generated**, everything else is hand-written |
| `Justfile` | Task runner for every workflow in this file |

---

## Setup

Prerequisites and first-run setup live in the README:
[Quick Start & Development Setup](README.md#-quick-start--development-setup).

Short version, once you have Bun 1.4+, JDK 25+, Podman (or Docker) and `just`:

```bash
bun install --cwd backend          # or: just install
cp backend/.env.example backend/.env   # then fill in JWT_SECRET
just db-up                          # PostgreSQL via Compose
bun run --cwd backend migrate
just dev
```

The toolchain versions are pinned in [`mise.toml`](mise.toml) — Bun 1.4+,
Temurin 25+, and the Android SDK. CI uses **Temurin 25**, so build with 25 to
reproduce it exactly.

---

## Before you commit

`just check` is the single gate. If it passes, CI's pull-request checks pass.
Everything below is a subset of it, for when you want a faster loop.

| Command | Covers | When to run |
|---|---|---|
| `just check-fast` | typecheck, eslint, ktlint, detekt, schema docs, `:shared:jvmTest`, `:android:assembleDebug` | before every commit (~20 s once warm) |
| `just check` | `check-fast` **+** the backend test suite in a throwaway Postgres | before every push / PR (~20 s once warm) |
| `just check-full` | `just check` **+** the WasmJs web bundle | before merging anything touching `app/shared/`, `app/web/`, or backend code that the web build consumes |

`just push` runs `just check` and then pushes, so you cannot forget it.

### What breaks if you skip a step

Each gate exists because something specific slipped through without it:

- **ktlint** — catches unused imports, import ordering, trailing whitespace and
  blank-line style in Kotlin. Cheap, and purely mechanical: `just lint-fix`
  rewrites it for you.
- **prettier** (`just fmt-check`) — the backend's formatting gate. It runs
  `prettier --check`, so it fails instead of rewriting; `just fmt` fixes it.
- **detekt** — flags swallowed exceptions, complexity, and other defects lint
  cannot see. Not auto-fixable; fix the code or justify a `@Suppress` with a
  reason.
- **`:shared:jvmTest`** — the KMP unit suite in `app/shared/src/commonTest`.
  Note it only compiles the JVM target.
- **`:android:assembleDebug`** — separate on purpose. `jvmTest` never compiles
  `androidMain`, so Android-only breakage passes every other gate and dies here
  (or worse, in the release build).
- **`web-build`** — `deploy-pages.yml` builds this on every push to `main`. If it
  breaks, the site stops deploying even though every PR check was green.
- **schema docs check** — `docs/schema.md` is generated from the Drizzle schema
  and CI fails if it is stale. Regenerate with `just gen-schema` after touching
  `backend/src/drizzle/`.

### What CI runs

The `CI` workflow has one filter job and two real jobs, and it only builds the
sides you touched (`backend/**` vs `app/**`):

```
Backend (lint, typecheck, test)   bun run typecheck · schema docs --check · bun test · eslint
Kotlin  (ktlint, detekt, test, build)
                                  ./gradlew ktlintCheck detekt
                                  ./gradlew :shared:jvmTest
                                  ./gradlew :android:assembleDebug
```

Two workflows are **not** part of the pull-request gate because they need macOS
or a release trigger: `ios-test.yml` (manual, `workflow_dispatch`) and
`release.yml` (on published releases). If you change `iosMain` code, run the iOS
job manually from the Actions tab — a broken iOS target will not be caught here.

> **Keep this list honest.** `check-fast` must stay a superset of CI's
> pull-request gate. If you add a step to `ci.yml`, add it to `check-fast` in the
> same PR, or the local gate silently rots and `main` starts failing.

---

## Style

Formatting is enforced, not discussed. `just check` fails if any file is not
already formatted — formatting is never fixed silently during a check.

```bash
just fmt-check     # verify only: prettier --check + ktlintCheck
just fmt           # rewrite: prettier -w + ktlintFormat
just lint-fix      # rewrite: eslint --fix + ktlintFormat
```

If `just fmt` changes files, commit those separately so the reformat is not
buried inside a behavioural change.

- **Backend (TypeScript)** — eslint + prettier. Types are checked with
  `tsc --noEmit`, which is stricter than `bun build`, so run `just typecheck`.
- **Kotlin** — ktlint for layout, detekt for quality. Prefer reusing the helpers
  in `app/shared/src/commonMain/.../utils/` (date formatting, error mapping)
  over re-implementing them in a screen.
- **Errors** — map failures through `toAppError()` and let `AuthEventManager`
  handle 401s globally. Re-throw `CancellationException`; never swallow it.
- **Never commit secrets.** `backend/.env`, `app/local.properties`, keystores and
  signing passwords are gitignored — keep it that way. CI supplies them as
  Actions secrets.

---

## Tests

Every bug fix deserves a test that fails without the fix. New behaviour in the
shared client should be testable without a UI: extract the logic (grouping,
filtering, formatting) into a plain function and test *that* — that is why
`RequestFormattingTest` exercises formatting helpers instead of rendering a
screen.

```bash
just app-test                  # KMP suite, JVM target
just test                      # backend suite; starts and tears down its own Postgres
cd app && ./gradlew :shared:jvmTest --tests '*RequestFormattingTest*'   # one class
cd backend && bun test tests/auth.test.ts                    # one file
```

`just test` sets `TEST_DATABASE_URL` for you and removes the container on exit.
If you run `bun test` directly, start `just db-up` first and export
`TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/iiitnr_inventory_test`,
or the DB-backed suites will fail to connect.

---

## Commits

Write commits that stand on their own, because history is not squashed — see
[How changes land](#how-changes-land).

- Conventional prefixes, imperative mood, no trailing period:
  `feat(ui): …`, `fix(backend): …`, `chore: …`, `test: …`, `ci: …`, `docs: …`,
  `refactor: …`. Component prefixes (`app:`, `backend:`) are also in the history.
- Subject ≤ ~72 characters; use the body to explain **why**, not what.
- One logical change per commit. A refactor and a behaviour change in the same
  commit are impossible to review and impossible to revert independently.
- Do not commit generated build output, and do not hand-edit `docs/schema.md`.

---

## Pull requests

1. Branch off `main`: `feat/short-description`, `fix/short-description`.
2. Make sure `just check` passes before you push.
3. Open the PR with:
   - **what changed** and **why**,
   - **how you verified it** (commands you ran, screenshots for UI work),
   - anything the reviewer should look at closely, and known gaps.
4. Keep it focused. Unrelated cleanups belong in their own PR.
5. CI must be green before merge. If a job is skipped, say why in the PR.

### Reviewing

Check that the diff actually matches the description, that new logic has a test,
and that no secrets, debug logging, or commented-out code came along for the
ride.

---

## How changes land

`main` keeps a **linear history**: no merge commits.

- Land PRs with **Rebase and merge** (or **Squash and merge** for a messy branch).
- **Never** use "Create a merge commit".
- If your branch is behind, rebase it — don't merge `main` into it:

```bash
git fetch origin
git rebase origin/main
git push --force-with-lease
```

Rebasing a branch you own is normal here. Use `--force-with-lease`, never a bare
`--force`, so you cannot clobber someone else's push.

If you are a maintainer updating a contributor's PR: contributors from forks
often allow maintainer edits (GitHub's "Allow edits by maintainers"). Rebase
locally, verify with `just check`, then force-push their branch so the PR updates
in place instead of opening a duplicate.

---

## Releasing

Maintainers only:

```bash
just release 1.18.0    # pushes main, creates a GitHub release with generated notes
just deploy            # rebuild backend container + publish web bundle
```

Bump `VERSION` and `backend/package.json` together — the backend reads `VERSION`
at build time and the client compares it against `/version`.

---

## License

By contributing you agree your work is released under the [MIT License](LICENSE).
