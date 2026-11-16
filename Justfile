set dotenv-load     := true
set dotenv-filename := "backend/.env"

set shell         := ["bash", "-uc"]
set windows-shell := ["cmd.exe", "/c"]

backend := "backend"
app     := "app"

img     := "iiitnr-inventory"
tag     := "latest"
port    := "4000"
uploads := "uploads"

test_db_url := "postgresql://postgres:postgres@localhost:5432/iiitnr_inventory_test"

gradle := if os() == "windows" { ".\\gradlew.bat" } else { "./gradlew" }

default:
    @just --choose

[group('setup')]
install:
    cd {{backend}} && bun install

[group('setup')]
update-deps:
    cd {{backend}} && bun update --latest && bun install
    just test

[group('dev')]
dev:
    cd {{backend}} && bun run dev

[group('dev')]
up:
    cd {{backend}} && podman compose up -d

[group('dev')]
down:
    cd {{backend}} && podman compose down

[group('dev')]
restart:
    cd {{backend}} && podman compose down && podman compose up -d

[group('dev')]
logs:
    cd {{backend}} && podman compose logs -f

[group('dev')]
db-up:
    cd {{backend}} && podman compose -f compose.db.yaml --profile test up -d

[group('dev')]
db-down:
    cd {{backend}} && podman compose -f compose.db.yaml --profile test down

[group('test')]
test: install
    #!/usr/bin/env bash
    set -euo pipefail
    cd {{backend}}
    trap 'podman compose -f compose.db.yaml --profile test down' EXIT
    podman compose -f compose.db.yaml --profile test up -d
    export TEST_DATABASE_URL="{{test_db_url}}"
    bun test --parallel=1 --only-failures

# Kotlin Multiplatform unit tests (JVM target) — the same suite CI runs.
[group('test')]
app-test:
    cd {{app}} && {{gradle}} :shared:jvmTest

# Compile the Android debug APK (also compiles androidMain, which jvmTest skips).
[group('test')]
app-build:
    cd {{app}} && {{gradle}} :android:assembleDebug

# Build the WasmJs web distribution, exactly as deploy-pages.yml does on main.
[group('test')]
web-build:
    cd {{app}} && {{gradle}} :web:wasmJsBrowserDistribution -Pkotlin.native.ignoreDisabledTargets=true

[group('quality')]
typecheck:
    cd {{backend}} && bun run typecheck

[group('quality')]
lint:
    cd {{backend}} && bun run lint
    cd {{app}} && {{gradle}} ktlintCheck

[group('quality')]
lint-fix:
    cd {{backend}} && bun run lint:fix
    cd {{app}} && {{gradle}} ktlintFormat

[group('quality')]
fmt:
    cd {{backend}} && bun run format
    cd {{app}} && {{gradle}} ktlintFormat

# Fails if anything is not already formatted. Never writes; use `just fmt` to fix.
[group('quality')]
fmt-check:
    cd {{backend}} && bun run format:check
    cd {{app}} && {{gradle}} ktlintCheck

[group('quality')]
detekt:
    cd {{app}} && {{gradle}} detekt

# Everything CI enforces on a PR, except the DB-backed backend tests. Run per commit.
# Steps run concurrently and a passing run prints one line.
[group('quality')]
check-fast *ARGS:
    @./bin/check.sh --fast {{ARGS}}

# Full pre-push gate: check-fast plus the backend test suite, in a throwaway Postgres.
[group('quality')]
check *ARGS:
    @./bin/check.sh {{ARGS}}

# check plus the web bundle deploy-pages ships. Run before merging shared/ or web/ changes.
[group('quality')]
check-full *ARGS:
    @./bin/check.sh --full {{ARGS}}

# Sequential equivalent of `check`, for environments without bash (e.g. Windows).
[group('quality')]
check-seq: typecheck lint detekt check-schema fmt-check app-test app-build test

[group('schema')]
gen-schema:
    cd {{backend}} && bun run scripts/generate-schema-docs.ts

[group('schema')]
check-schema:
    cd {{backend}} && bun run scripts/generate-schema-docs.ts --check

web:
    cd {{app}} && {{gradle}} :web:wasmJsBrowserDevelopmentRun -PappDebug=true

[unix]
desk:
    cd {{app}} && _JAVA_AWT_WM_NONREPARENTING=1 {{gradle}} desktop:run -PappDebug=true

[windows]
desk:
    cd {{app}} && {{gradle}} desktop:run -PappDebug=true

[unix]
andro:
    rm -f {{app}}/android/build/outputs/apk/debug/com.iiitnr.inventoryapp-*.apk
    cd {{app}} && {{gradle}} assembleDebug -PappDebug=true
    adb install {{app}}/android/build/outputs/apk/debug/com.iiitnr.inventoryapp-*.apk

[windows]
andro:
    del /q {{app}}\android\build\outputs\apk\debug\com.iiitnr.inventoryapp-*.apk 2>nul
    cd {{app}} && {{gradle}} assembleDebug -PappDebug=true
    for %%f in ({{app}}\android\build\outputs\apk\debug\com.iiitnr.inventoryapp-*.apk) do adb install %%f


[group('container')]
image:
    cd {{backend}} && cp ../VERSION ./VERSION && podman build -t {{img}}:{{tag}} .

[group('container')]
size: image
    podman images {{img}}:{{tag}}
    podman history {{img}}:{{tag}} | head -12

[group('container')]
run:
    cd {{backend}} && podman run --rm --name backend-dev \
      --env-file .env \
      -p {{port}}:{{port}} \
      -v ./uploads:/app/uploads \
      {{img}}:{{tag}}

[group('container')]
run-ro:
    cd {{backend}} && podman run --rm --name backend-dev-ro \
      --read-only --tmpfs /tmp \
      --env-file .env -e SKIP_SEED=1 \
      -p {{port}}:{{port}} \
      -v ./uploads:/app/uploads \
      {{img}}:{{tag}}

[group('container')]
seed:
    cd {{backend}} && podman run --rm --env-file .env \
      --entrypoint /usr/local/bin/bun {{img}}:{{tag}} run scripts/seed.ts

[unix]
deploy-web:
    @if [ -z "${GOOGLE_WEB_CLIENT_ID:-}" ]; then \
      echo "ERROR: GOOGLE_WEB_CLIENT_ID not set (check backend/.env)"; exit 1; fi
    cd {{app}} && {{gradle}} :web:wasmJsBrowserDistribution -Pkotlin.native.ignoreDisabledTargets=true
    mkdir -p /srv/web
    cp -r {{app}}/web/build/dist/wasmJs/productionExecutable/. /srv/web/
    echo "Web app deployed to /srv/web"

[unix]
deploy:
    git pull
    cp VERSION {{backend}}/VERSION
    just deploy-web
    podman tag localhost/backend_backend:latest localhost/backend_backend:previous
    podman compose -f {{backend}}/compose.prod.yaml up -d --build --force-recreate --remove-orphans backend
    @just verify

[unix]
verify:
    #!/usr/bin/env bash
    set -euo pipefail

    c=backend-backend-1
    start=$SECONDS
    healthy=0

    for _ in $(seq 1 30); do
      if podman healthcheck run "$c" >/dev/null 2>&1; then
        healthy=1
        break
      fi
      sleep 2
    done

    if [ "$healthy" -ne 1 ]; then
      echo "❌ backend never became healthy"
      podman logs --tail 50 "$c" || true
      exit 1
    fi
    echo "✅ backend healthy after $((SECONDS - start))s"

    curl -fsS -o /dev/null -w 'site: %{http_code}\n' https://inventory.iiitnr.ac.in/

[unix]
previous:
    #!/usr/bin/env bash
    set -euo pipefail
    podman tag localhost/backend_backend:latest localhost/backend_backend:failed || true
    podman tag localhost/backend_backend:previous localhost/backend_backend:latest
    podman compose -f {{backend}}/compose.prod.yaml up -d --force-recreate backend
    echo "rolled back to $(podman inspect localhost/backend_backend:latest --format '{{{{.ID}}}}' | cut -c1-12)"

[unix]
push: check
    git push

[unix]
release version:
    git push origin main
    gh release create v{{version}} --target main --generate-notes