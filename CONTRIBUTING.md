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
| `just check-fast` | typecheck, eslint, prettier, ktlint, detekt, schema docs, `:shared:jvmTest`, `:android:assembleDebug` | before every commit (~8 s once warm) |
| `just check` | `check-fast` **+** the backend test suite in a throwaway Postgres | before every push / PR (~16 s once warm) |
| `just check-full` | `just check` **+** the WasmJs web bundle | before merging anything touching `app/shared/`, `app/web/`, or backend code that the web build consumes |

`just push` runs `just check` and then pushes, so you cannot forget it.

The steps run concurrently and the output is deliberately quiet: a passing run
prints one line, and a failing run prints only the steps that failed.

```bash
just check                      # ✓ check passed (3 steps, 16.0s)
just check-fast -v              # per-step timings, when one step got slow
CHECK_VERBOSE=1 just check      # same, via the environment
```

**If a run feels slow, check memory before blaming the code.** Gradle is
configured with `-Xmx6g`, so it will happily swap an 8 GB machine. Concurrency
is capped at two steps by default (`CHECK_JOBS`); raising it only helps when
there is headroom, and the backend suite is usually the long pole regardless.
`CHECK_GRADLE_PARALLEL=1` enables cross-project Gradle parallelism.

The gate needs bash. On Windows use `just check-seq`, which runs the same steps
sequentially.

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

## UI & Responsive Layout Conventions

The client is one Compose codebase running on phones, desktop and the web, so
these rules are what keep a screen looking the same everywhere. The design
system lives in `app/shared/src/commonMain/.../ui/` — extend it there rather
than re-inventing pieces per screen.

### Width caps

Two constants in `ui/components/common/Responsive.kt` cover every screen:

| Constant | Width | Use for |
|---|---|---|
| `WideContentWidth` | `1280.dp` | adaptive grids, and `Expanded` (`>= 900.dp`) Master–Detail layouts |
| `NarrowContentWidth` | `900.dp` | single-column screens, and the `AppTopBar` of focused screens |

- Wrap responsive screens in `ResponsiveContent { widthClass -> … }` and branch
  on `ScreenWidthClass.Compact` / `Medium` / `Expanded`.
- Focused-screen bodies stay narrower than the bar above them: auth forms are
  `420.dp` and the profile body is `560.dp`, centred inside
  `NarrowContentWidth`.
- Do **not** hardcode `1280.dp`, `1000.dp`, `900.dp` or `800.dp` caps in a
  screen. If a new tier is genuinely needed, add it to `Responsive.kt` first.

### Headers, dividers and footers

- Pass `maxContentWidth` to `AppTopBar` and `PaginationBar` so titles, actions
  and page controls line up with the content grid; the bar and footer
  backgrounds still span the full window.
- Bar titles use `colorScheme.onSurface`. `primary` is for card and list-item
  titles, not for the bar.
- Put a full-width
  `HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)` below a
  fixed header block (search + filters) and below `AppTopBar`, before the
  scrollable content. The divider stays edge-to-edge even when the content above
  it is capped.

### Cards, tints and badges

- **Neutral cards** — `surfaceContainerLow`, `elevation = 0.dp`, and
  `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`. No drop
  shadows; the hairline border does the separating.
- **Semantic-tinted cards** — blend the hue onto the surface instead of stacking
  alpha:

  ```kotlin
  lerp(
      start = MaterialTheme.colorScheme.surfaceContainerLow,
      stop = semanticColor,
      fraction = if (LocalIsDarkTheme.current) 0.14f else 0.07f,
  )
  ```

  Use `0.22f` dark / `0.12f` light when the row is selected (`RequestCard`).
  Borders are the semantic hue at 35% alpha, `2.dp` when selected
  (`RequestCard`, `AuditLogCard`); neutral selectable cards select with `1.5.dp`
  `primary`.
- **Badges and pills** — `0.24f` alpha in dark, `0.14f` in light.

### Theming

Never call `isSystemInDarkTheme()` inside a composable. Read
`LocalIsDarkTheme.current`; the client's only `isSystemInDarkTheme()` call lives
in `ThemeMode.resolveIsDark()`, which resolves the persisted `ThemeMode`.
Semantic colours come from `MaterialTheme.inventoryColors`.

### Controls and ergonomics

- Filters are borderless `TextButton` pills — selected is `primary` (or the
  relevant semantic colour), unselected is `onSurfaceVariant`. No `FilterChip`.
- Dropdowns use `ExposedDropdownMenu` with
  `Modifier.exposedDropdownSize(matchAnchorWidth = true)`, so the menu matches
  the field instead of shrinking to its widest item.
- Images use `SubcomposeAsyncImage` with a category/icon placeholder for
  `loading`, for `error`, and for a null URL — never a broken-image glyph.
- Icon-only actions get a `TooltipBox`; clickable elements get
  `Modifier.handCursor()`.
- Never stretch a button full-width on `Expanded`; buttons belong in a dialog or
  inside a capped column.

### UI verification

`just check` stays the required gate, but it compiles only the JVM and Android
targets. For anything touching `app/shared/` UI, also compile every target:

```bash
cd app && ./gradlew ktlintCheck detekt \
  :desktop:compileKotlinJvm :shared:compileKotlinWasmJs \
  :web:compileKotlinWasmJs :android:compileDebugKotlin :shared:jvmTest
```

That catches a Compose API that exists on the JVM but not in the web toolchain,
which `jvmTest` alone happily misses.

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
2. Make sure `just check` passes before you push. For UI changes, also run the
   per-target gate in
   [UI & Responsive Layout Conventions](#ui--responsive-layout-conventions).
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
