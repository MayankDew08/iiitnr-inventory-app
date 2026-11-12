FROM docker.io/oven/bun:1.4.4-slim AS builder
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
RUN bun run typecheck

# ---------- Prod deps: clean slate, dev deps never installed ----------
FROM docker.io/oven/bun:1.4.4-slim AS prod-deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production

FROM docker.io/oven/bun:1.4.4-slim AS uploads
RUN mkdir -p /app/uploads/images && chown -R 65532:65532 /app/uploads

FROM docker.io/oven/bun:1.4.4-distroless AS runner
WORKDIR /app
COPY --from=prod-deps --chown=65532:65532 /app/node_modules ./node_modules
COPY --from=builder   --chown=65532:65532 /app/src ./src
COPY --from=builder   --chown=65532:65532 /app/scripts ./scripts
COPY --from=builder   --chown=65532:65532 /app/package.json ./package.json
COPY --from=builder   --chown=65532:65532 /app/entrypoint.ts ./entrypoint.ts
COPY --from=uploads   --chown=65532:65532 /app/uploads ./uploads
EXPOSE 4000

#HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
#  CMD ["/usr/local/bin/bun", "-e", "fetch('http://127.0.0.1:4000/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/bun", "run", "entrypoint.ts"]