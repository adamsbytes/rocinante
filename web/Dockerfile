FROM oven/bun:1 AS base
WORKDIR /app

# Install dependencies
FROM base AS deps
COPY package.json bun.lock* ./
RUN bun install --frozen-lockfile

# Build the application
FROM base AS builder
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN bun run build

# Production image
FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000
ENV CONFIG_PATH=/data/bots.json
ENV BOT_IMAGE=rocinante-bot:latest

# Create data directory
RUN mkdir -p /data

# Copy built assets
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/package.json ./

# Install production dependencies only
COPY --from=deps /app/node_modules ./node_modules

EXPOSE 3000

CMD ["bun", "run", "start"]
