set windows-shell := ["cmd.exe", "/c"]
set dotenv-load := true
set dotenv-filename := "backend/.env"

default: dev

install:
    cd backend && bun install

up-dep:
    cd backend && bun update --latest && bun install && just test

image:
    cd backend && podman build -t test . && podman images test && podman run --env-file .env -p4000:4000 test

dev:
    cd backend && bun run dev

db-up:
    cd backend && podman compose -f compose.db.yaml --profile test up -d

db-down:
    cd backend && podman compose -f compose.db.yaml --profile test down

up:
    cd backend && podman compose up -d

down:
    cd backend && podman compose down

restart:
    cd backend && podman compose down
    cd backend && podman compose up -d

logs:
    cd backend && podman compose logs -f

test:
    just db-down
    just install
    cd backend && podman-compose -f compose.db.yaml --profile test up -d
    export TEST_DATABASE_URL="postgresql://postgres:postgres@localhost:5432/iiitnr_inventory_test"; cd backend && bun test --parallel=1
    cd backend && podman-compose -f compose.db.yaml --profile test down

[unix]
desk:
    cd app && _JAVA_AWT_WM_NONREPARENTING=1 ./gradlew desktop:run -PappDebug=true

[windows]
desk:
    cd app && .\gradlew.bat desktop:run -PappDebug=true

[unix]
andro:
    rm -f app/android/build/outputs/apk/debug/com.iiitnr.inventoryapp-*.apk
    cd app && ./gradlew assembleDebug -PappDebug=true
    adb install app/android/build/outputs/apk/debug/com.iiitnr.inventoryapp-*.apk

[windows]
andro:
    del /q app\android\build\outputs\apk\debug\com.iiitnr.inventoryapp-*.apk 2>nul
    cd app && .\gradlew.bat assembleDebug -PappDebug=true
    for %f in (app\android\build\outputs\apk\debug\com.iiitnr.inventoryapp-*.apk) do adb install %f


[unix]
lint:
    cd backend && bun run lint
    cd app && ./gradlew ktlintCheck

[windows]
lint:
    cd backend && bun run lint
    cd app && .\gradlew.bat ktlintCheck

[unix]
lint-fix:
    cd backend && bun run lint:fix
    cd app && ./gradlew ktlintFormat

[windows]
lint-fix:
    cd backend && bun run lint:fix
    cd app && .\gradlew.bat ktlintFormat

typecheck:
    cd backend && bun run typecheck

gen-schema:
    cd backend && bun run scripts/generate-schema-docs.ts

check-schema:
    cd backend && bun run scripts/generate-schema-docs.ts --check

[unix]
fmt:
    cd backend && bun run format
    cd app && ./gradlew ktlintFormat

[windows]
fmt:
    cd backend && bun run format
    cd app && .\gradlew.bat ktlintFormat

[unix]
detekt:
    cd app && ./gradlew detekt

[windows]
detekt:
    cd app && .\gradlew.bat detekt


[unix]
web:
    cd app && ./gradlew :web:wasmJsBrowserDevelopmentRun -PappDebug=true

[windows]
web:
    cd app && ./gradlew :web:wasmJsBrowserDevelopmentRun -PappDebug=true

[unix]
deploy-web:
    @if [ -z "$GOOGLE_WEB_CLIENT_ID" ]; then echo "ERROR: GOOGLE_WEB_CLIENT_ID not loaded from backend/.env"; exit 1; fi
    cd app && ./gradlew :web:wasmJsBrowserDistribution -Pkotlin.native.ignoreDisabledTargets=true
    mkdir -p /srv/web
    cp -r app/web/build/dist/wasmJs/productionExecutable/. /srv/web/
    echo "Web app deployed to /srv/web"

deploy:
    git pull
    just deploy-web
    podman compose -f backend/compose.prod.yaml down
    podman compose -f backend/compose.prod.yaml up --build -d --force-recreate backend

release version:
    git push origin main
    gh release create v{{version}} --target main --generate-notes

push:
    cd backend && bun run typecheck
    # cd backend && bun run lint
    check-schema
    just test
    just detekt
    git push
