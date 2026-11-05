FROM mcr.microsoft.com/playwright:v1.63.0-noble

WORKDIR /app
COPY ai-worker/package.json ./package.json
COPY ai-worker/package-lock.json ./package-lock.json
RUN npm ci --omit=dev --no-audit --no-fund
COPY ai-worker/src ./src
ENV NODE_ENV=production
EXPOSE 3001
USER pwuser
CMD ["node", "src/server.js"]
