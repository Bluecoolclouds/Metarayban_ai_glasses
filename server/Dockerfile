FROM mcr.microsoft.com/playwright:v1.58.0-noble

RUN apt-get update && apt-get install -y ffmpeg unzip && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package*.json ./
RUN npm ci --production
RUN npx playwright install chromium --with-deps
COPY . .

RUN mkdir -p /app/uploads

EXPOSE 5000
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright
CMD ["node", "index.js"]
