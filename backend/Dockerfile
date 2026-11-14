FROM docker.io/oven/bun:1.4.4-slim AS deps
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production
RUN mkdir -p /app/uploads/images && chown -R 65532:65532 /app/uploads

FROM docker.io/oven/bun:1.4.4-distroless AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps --chown=65532:65532 /app/node_modules ./node_modules
COPY --from=deps --chown=65532:65532 /app/uploads      ./uploads
COPY --chown=65532:65532 src         ./src
COPY --chown=65532:65532 scripts     ./scripts
COPY --chown=65532:65532 VERSION entrypoint.ts ./
EXPOSE 4000
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/bun", "run", "entrypoint.ts"]