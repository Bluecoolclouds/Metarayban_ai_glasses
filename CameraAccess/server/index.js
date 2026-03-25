const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const { exec } = require("child_process");
const { WebSocketServer } = require("ws");
const busboy = require("busboy");
const TwitchRestreamer = require("./twitch-restreamer");
const Anthropic = require("@anthropic-ai/sdk");
const { startTelegramBot, getBotInstance, sendTelegramMessage, getLastActiveChatId, buildSystemPrompt, parseResponse, executeActions, setSendToGlasses, setTools } = require("./telegram-bot");
const memory = require("./memory");
const aiProviders = require("./ai-providers");
const avito = require("./avito");

const OPENCLAW_TOOLS = [
  {
    name: "execute_command",
    description: "Execute a shell command on the server. Use for system tasks, checking status, installing packages, managing files, running scripts. Commands have a 30-second timeout. Avoid destructive commands (rm -rf /, etc).",
    input_schema: {
      type: "object",
      properties: {
        command: { type: "string", description: "The shell command to execute" },
        timeout_ms: { type: "number", description: "Timeout in ms (default 30000, max 60000)" },
      },
      required: ["command"],
    },
  },
  {
    name: "http_request",
    description: "Make an HTTP/HTTPS request. Use for calling APIs, checking websites, downloading data, webhooks.",
    input_schema: {
      type: "object",
      properties: {
        url: { type: "string", description: "Full URL to request" },
        method: { type: "string", enum: ["GET", "POST", "PUT", "DELETE", "PATCH"], description: "HTTP method (default GET)" },
        headers: { type: "object", description: "Request headers as key-value pairs" },
        body: { type: "string", description: "Request body (for POST/PUT/PATCH)" },
      },
      required: ["url"],
    },
  },
  {
    name: "read_file",
    description: "Read the contents of a file on the server.",
    input_schema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Absolute or relative file path" },
        max_lines: { type: "number", description: "Max lines to read (default 200)" },
      },
      required: ["path"],
    },
  },
  {
    name: "write_file",
    description: "Write content to a file on the server. Creates parent directories if needed.",
    input_schema: {
      type: "object",
      properties: {
        path: { type: "string", description: "File path to write" },
        content: { type: "string", description: "Content to write" },
        append: { type: "boolean", description: "Append instead of overwrite (default false)" },
      },
      required: ["path", "content"],
    },
  },
  {
    name: "list_files",
    description: "List files and directories at a given path.",
    input_schema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Directory path (default: current directory)" },
      },
      required: [],
    },
  },
  {
    name: "web_search",
    description: "Search the web using DuckDuckGo. Returns top results with titles, URLs, and snippets. Use for finding information, documentation, news, tutorials, etc.",
    input_schema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Search query" },
        max_results: { type: "number", description: "Max results to return (default 5, max 10)" },
      },
      required: ["query"],
    },
  },
  {
    name: "weather",
    description: "Get current weather and forecast for a location. Uses Open-Meteo API (free, no key needed). Returns temperature, humidity, wind, conditions.",
    input_schema: {
      type: "object",
      properties: {
        location: { type: "string", description: "City name or 'lat,lon' coordinates" },
      },
      required: ["location"],
    },
  },
  {
    name: "github_api",
    description: "Call GitHub API. Requires GITHUB_TOKEN env var. Can list repos, issues, PRs, create issues, read files, etc. Uses GitHub REST API v3.",
    input_schema: {
      type: "object",
      properties: {
        endpoint: { type: "string", description: "API endpoint path (e.g., /user/repos, /repos/owner/repo/issues)" },
        method: { type: "string", enum: ["GET", "POST", "PUT", "PATCH", "DELETE"], description: "HTTP method (default GET)" },
        body: { type: "object", description: "Request body for POST/PUT/PATCH" },
      },
      required: ["endpoint"],
    },
  },
  {
    name: "ssh_command",
    description: "Execute a command on a remote server via SSH. Requires ssh key to be set up or password. Uses the ssh command-line tool.",
    input_schema: {
      type: "object",
      properties: {
        host: { type: "string", description: "Remote host (user@hostname or just hostname)" },
        command: { type: "string", description: "Command to execute on remote server" },
        port: { type: "number", description: "SSH port (default 22)" },
        key_path: { type: "string", description: "Path to SSH private key file (optional)" },
        timeout_ms: { type: "number", description: "Timeout in ms (default 30000)" },
      },
      required: ["host", "command"],
    },
  },
  {
    name: "send_telegram",
    description: "Send a Telegram message to the user. Use this when the user asks you to send them a message, reminder, or notification via Telegram. If no chat_id is provided, sends to the default configured chat.",
    input_schema: {
      type: "object",
      properties: {
        message: { type: "string", description: "The message text to send. Supports Markdown formatting." },
        chat_id: { type: "string", description: "Telegram chat ID to send to (optional, uses default if not specified)" },
      },
      required: ["message"],
    },
  },
  {
    name: "browse_web",
    description: "Open a real browser (Playwright/Chromium with stealth) and interact with any website. Supports JavaScript-heavy sites, login forms, clicking buttons, filling forms, extracting data. Use for sites that require JS rendering (flight search, ticket booking, social media, online banking, etc). The browser opens fresh, does the task, then closes.",
    input_schema: {
      type: "object",
      properties: {
        url: { type: "string", description: "URL to open" },
        actions: {
          type: "array",
          description: "List of actions to perform on the page",
          items: {
            type: "object",
            properties: {
              type: { type: "string", enum: ["wait", "click", "fill", "extract", "screenshot", "scroll", "evaluate"], description: "Action type" },
              selector: { type: "string", description: "CSS selector for click/fill/extract actions" },
              value: { type: "string", description: "Text to fill in, JS to evaluate, or ms to wait" },
              description: { type: "string", description: "What this action does (for logging)" },
            },
          },
        },
        extract_text: { type: "boolean", description: "Return full page text content after all actions (default true)" },
        timeout_ms: { type: "number", description: "Timeout for page load in ms (default 30000)" },
      },
      required: ["url"],
    },
  },
  {
    name: "analyze_camera",
    description: "Capture and analyze the current camera frame from the user's smart glasses or phone. Use when the user wants you to look at something, identify an object, read text, describe a scene, or answer questions about what the camera sees. Requires the device to be connected and streaming to Gemini.",
    input_schema: {
      type: "object",
      properties: {
        question: { type: "string", description: "What to look for or analyze in the image (e.g. 'What is this object?', 'Read the text on this label', 'Describe the scene'). Defaults to a general description if not specified." },
      },
      required: [],
    },
  },
  {
    name: "camera_mode",
    description: "Enable or disable Gemini's live camera vision. When enabled, Gemini can see the camera in real-time for 5 minutes (auto-disabled after timeout). Use when user wants Gemini to actively watch/see something. Disable when user says 'хватит смотреть', 'выключи камеру', 'stop watching', etc.",
    input_schema: {
      type: "object",
      properties: {
        enabled: { type: "boolean", description: "true to enable camera vision for 5 minutes, false to disable immediately" },
      },
      required: ["enabled"],
    },
  },
  {
    name: "send_file",
    description: "Send a file from the server to the user via Telegram. Use after downloading or creating files with http_request/execute_command/write_file. Supports photos, documents, audio, video. Auto-detects type by extension.",
    input_schema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Absolute or relative path to the file on the server" },
        caption: { type: "string", description: "Optional caption/description for the file" },
        type: { type: "string", enum: ["auto", "document", "photo", "audio", "video"], description: "File type (default: auto — detected by extension)" },
      },
      required: ["path"],
    },
  },
  {
    name: "avito_profile",
    description: "Get the Avito account profile: user id, name, email, phone, balance. Use to confirm the account is connected.",
    input_schema: { type: "object", properties: { _noop: { type: "string", description: "Ignored" } }, required: [] },
  },
  {
    name: "avito_chats",
    description: "Get list of Avito messenger chats. Returns buyer info, last message, item title, unread count. Use to see who is writing and about what.",
    input_schema: {
      type: "object",
      properties: {
        limit: { type: "number", description: "Number of chats to return (default 20)" },
        offset: { type: "number", description: "Pagination offset (default 0)" },
        unread_only: { type: "boolean", description: "Return only chats with unread messages (default false)" },
      },
      required: [],
    },
  },
  {
    name: "avito_messages",
    description: "Get messages from a specific Avito chat. Use to read conversation history with a buyer.",
    input_schema: {
      type: "object",
      properties: {
        chat_id: { type: "string", description: "Chat ID from avito_chats" },
        limit: { type: "number", description: "Number of messages (default 20)" },
      },
      required: ["chat_id"],
    },
  },
  {
    name: "avito_send_message",
    description: "Send a text message to a buyer in an Avito chat. Use to reply to inquiries, negotiate, confirm deals.",
    input_schema: {
      type: "object",
      properties: {
        chat_id: { type: "string", description: "Chat ID from avito_chats" },
        text: { type: "string", description: "Message text to send" },
      },
      required: ["chat_id", "text"],
    },
  },
  {
    name: "avito_items",
    description: "Get the list of your Avito listings (ads). Returns item id, title, price, status, views, contacts count.",
    input_schema: {
      type: "object",
      properties: {
        limit: { type: "number", description: "Number of items (default 25)" },
        offset: { type: "number", description: "Pagination offset (default 0)" },
        status: { type: "string", enum: ["active", "inactive", "blocked", "old"], description: "Filter by status (optional)" },
      },
      required: [],
    },
  },
  {
    name: "avito_categories",
    description: "Search Avito categories to find the right category_id for creating an ad. Returns category list with ids and names. Use before avito_create_ad to find the correct category.",
    input_schema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Category name to search (e.g. 'электроника', 'одежда', 'мебель'). Leave empty for top-level categories." },
      },
      required: [],
    },
  },
  {
    name: "avito_category_params",
    description: "Get required and optional parameters for a specific Avito category. Call this before avito_create_ad to know what params to fill in. Returns param ids, names, types and allowed values.",
    input_schema: {
      type: "object",
      properties: {
        category_id: { type: "number", description: "Category ID from avito_categories" },
      },
      required: ["category_id"],
    },
  },
  {
    name: "avito_create_ad",
    description: "Create a new Avito listing (ad). Call avito_categories first to find the right category_id, then avito_category_params to get required params. Location 621540 = Moscow by default.",
    input_schema: {
      type: "object",
      properties: {
        category_id: { type: "number", description: "Category ID from avito_categories" },
        title: { type: "string", description: "Ad title (up to 50 characters)" },
        description: { type: "string", description: "Full ad description" },
        price: { type: "number", description: "Price in rubles (0 for free)" },
        location_id: { type: "number", description: "City ID. 621540 = Moscow, 637640 = Saint Petersburg, 649295 = Novosibirsk. Default: 621540" },
        params: {
          type: "array",
          description: "Category-specific parameters from avito_category_params. Example: [{\"id\": 24900, \"values\": [{\"id\": 123}]}]",
          items: {
            type: "object",
            properties: {
              id: { type: "number", description: "Parameter ID" },
              values: { type: "array", items: { type: "object", properties: { id: { type: "number" } } }, description: "Array of value objects with id" },
            },
          },
        },
        contact_phone: { type: "string", description: "Contact phone number (optional, uses account phone by default)" },
      },
      required: ["category_id", "title", "description", "price"],
    },
  },
  {
    name: "avito_update_ad_status",
    description: "Activate or deactivate an Avito listing. Use to publish, pause or close an ad.",
    input_schema: {
      type: "object",
      properties: {
        item_id: { type: "number", description: "Ad ID from avito_items" },
        status: { type: "string", enum: ["active", "inactive"], description: "New status: active = publish, inactive = pause" },
      },
      required: ["item_id", "status"],
    },
  },
];

async function executeToolCall(toolName, input) {
  switch (toolName) {
    case "execute_command": {
      const timeout = Math.min(input.timeout_ms || 30000, 60000);
      return new Promise((resolve) => {
        exec(input.command, { timeout, maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
          if (err && err.killed) {
            resolve({ error: `Command timed out after ${timeout}ms`, stdout: stdout?.substring(0, 2000) || "", stderr: stderr?.substring(0, 2000) || "" });
          } else if (err) {
            resolve({ exit_code: err.code, stdout: stdout?.substring(0, 4000) || "", stderr: stderr?.substring(0, 4000) || "" });
          } else {
            resolve({ exit_code: 0, stdout: stdout?.substring(0, 4000) || "", stderr: stderr?.substring(0, 500) || "" });
          }
        });
      });
    }

    case "http_request": {
      const method = input.method || "GET";
      const headers = input.headers || {};
      return new Promise((resolve) => {
        const urlObj = new URL(input.url);
        const lib = urlObj.protocol === "https:" ? https : http;
        const opts = {
          hostname: urlObj.hostname,
          port: urlObj.port,
          path: urlObj.pathname + urlObj.search,
          method,
          headers,
          timeout: 15000,
        };
        const req = lib.request(opts, (res) => {
          let data = "";
          res.on("data", (chunk) => { data += chunk; });
          res.on("end", () => {
            resolve({
              status: res.statusCode,
              headers: res.headers,
              body: data.substring(0, 8000),
            });
          });
        });
        req.on("error", (e) => resolve({ error: e.message }));
        req.on("timeout", () => { req.destroy(); resolve({ error: "Request timed out" }); });
        if (input.body) req.write(input.body);
        req.end();
      });
    }

    case "read_file": {
      try {
        const content = fs.readFileSync(input.path, "utf-8");
        const lines = content.split("\n");
        const maxLines = input.max_lines || 200;
        const truncated = lines.length > maxLines;
        return {
          content: lines.slice(0, maxLines).join("\n"),
          total_lines: lines.length,
          truncated,
        };
      } catch (e) {
        return { error: e.message };
      }
    }

    case "write_file": {
      try {
        const dir = path.dirname(input.path);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
        if (input.append) {
          fs.appendFileSync(input.path, input.content);
        } else {
          fs.writeFileSync(input.path, input.content);
        }
        return { success: true, path: input.path, bytes: Buffer.byteLength(input.content) };
      } catch (e) {
        return { error: e.message };
      }
    }

    case "list_files": {
      try {
        const dirPath = input.path || ".";
        const entries = fs.readdirSync(dirPath, { withFileTypes: true });
        return {
          path: dirPath,
          entries: entries.map(e => ({
            name: e.name,
            type: e.isDirectory() ? "directory" : "file",
          })),
        };
      } catch (e) {
        return { error: e.message };
      }
    }

    case "web_search": {
      const maxResults = Math.min(input.max_results || 5, 10);
      const query = encodeURIComponent(input.query);
      return new Promise((resolve) => {
        const url = `https://html.duckduckgo.com/html/?q=${query}`;
        const req = https.request(url, { method: "GET", headers: { "User-Agent": "Mozilla/5.0" }, timeout: 10000 }, (res) => {
          let data = "";
          res.on("data", (chunk) => { data += chunk; });
          res.on("end", () => {
            const results = [];
            const regex = /<a rel="nofollow" class="result__a" href="([^"]*)"[^>]*>(.*?)<\/a>[\s\S]*?<a class="result__snippet"[^>]*>(.*?)<\/a>/g;
            let match;
            while ((match = regex.exec(data)) && results.length < maxResults) {
              results.push({
                url: match[1].replace(/&amp;/g, "&"),
                title: match[2].replace(/<\/?b>/g, "").replace(/&amp;/g, "&").replace(/&#x27;/g, "'").replace(/&quot;/g, '"'),
                snippet: match[3].replace(/<\/?b>/g, "").replace(/&amp;/g, "&").replace(/&#x27;/g, "'").replace(/&quot;/g, '"'),
              });
            }
            resolve({ query: input.query, results, count: results.length });
          });
        });
        req.on("error", (e) => resolve({ error: e.message }));
        req.on("timeout", () => { req.destroy(); resolve({ error: "Search timed out" }); });
        req.end();
      });
    }

    case "weather": {
      const location = input.location;
      const fetchJSON = (url) => new Promise((resolve, reject) => {
        https.get(url, { timeout: 10000 }, (res) => {
          let data = "";
          res.on("data", (chunk) => { data += chunk; });
          res.on("end", () => { try { resolve(JSON.parse(data)); } catch(e) { reject(e); } });
        }).on("error", reject);
      });

      try {
        let lat, lon, placeName;
        if (/^-?\d+\.?\d*\s*,\s*-?\d+\.?\d*$/.test(location)) {
          [lat, lon] = location.split(",").map(s => parseFloat(s.trim()));
          placeName = `${lat},${lon}`;
        } else {
          const geo = await fetchJSON(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(location)}&count=1`);
          if (!geo.results || !geo.results.length) return { error: `Location not found: ${location}` };
          lat = geo.results[0].latitude;
          lon = geo.results[0].longitude;
          placeName = `${geo.results[0].name}, ${geo.results[0].country || ""}`;
        }
        const weather = await fetchJSON(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=auto&forecast_days=3`);
        const wmo = { 0: "Clear", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Rime fog", 51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 61: "Light rain", 63: "Rain", 65: "Heavy rain", 71: "Light snow", 73: "Snow", 75: "Heavy snow", 80: "Light showers", 81: "Showers", 82: "Heavy showers", 95: "Thunderstorm", 96: "Thunderstorm+hail" };
        return {
          location: placeName,
          current: {
            temperature: `${weather.current.temperature_2m}°C`,
            humidity: `${weather.current.relative_humidity_2m}%`,
            wind: `${weather.current.wind_speed_10m} km/h`,
            conditions: wmo[weather.current.weather_code] || `Code ${weather.current.weather_code}`,
          },
          forecast: weather.daily.time.map((d, i) => ({
            date: d,
            high: `${weather.daily.temperature_2m_max[i]}°C`,
            low: `${weather.daily.temperature_2m_min[i]}°C`,
            conditions: wmo[weather.daily.weather_code[i]] || `Code ${weather.daily.weather_code[i]}`,
          })),
        };
      } catch (e) {
        return { error: e.message };
      }
    }

    case "github_api": {
      const token = process.env.GITHUB_TOKEN;
      if (!token) return { error: "GITHUB_TOKEN env var not set. Ask the user to provide it." };
      const method = input.method || "GET";
      const endpoint = input.endpoint.startsWith("/") ? input.endpoint : `/${input.endpoint}`;
      const bodyStr = input.body ? JSON.stringify(input.body) : null;
      return new Promise((resolve) => {
        const opts = {
          hostname: "api.github.com",
          path: endpoint,
          method,
          headers: {
            "Authorization": `Bearer ${token}`,
            "Accept": "application/vnd.github+json",
            "User-Agent": "OpenClaw-Agent",
            "X-GitHub-Api-Version": "2022-11-28",
            ...(bodyStr ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(bodyStr) } : {}),
          },
          timeout: 15000,
        };
        const req = https.request(opts, (res) => {
          let data = "";
          res.on("data", (chunk) => { data += chunk; });
          res.on("end", () => {
            try { resolve({ status: res.statusCode, data: JSON.parse(data.substring(0, 8000)) }); }
            catch { resolve({ status: res.statusCode, data: data.substring(0, 8000) }); }
          });
        });
        req.on("error", (e) => resolve({ error: e.message }));
        req.on("timeout", () => { req.destroy(); resolve({ error: "Request timed out" }); });
        if (bodyStr) req.write(bodyStr);
        req.end();
      });
    }

    case "ssh_command": {
      const port = input.port || 22;
      const timeout = Math.min(input.timeout_ms || 30000, 60000);
      const keyArg = input.key_path ? `-i ${input.key_path}` : "";
      const sshCmd = `ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -p ${port} ${keyArg} ${input.host} ${JSON.stringify(input.command)}`;
      return new Promise((resolve) => {
        exec(sshCmd, { timeout, maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
          if (err && err.killed) {
            resolve({ error: `SSH timed out after ${timeout}ms`, stdout: stdout?.substring(0, 2000) || "", stderr: stderr?.substring(0, 2000) || "" });
          } else if (err) {
            resolve({ exit_code: err.code, stdout: stdout?.substring(0, 4000) || "", stderr: stderr?.substring(0, 4000) || "" });
          } else {
            resolve({ exit_code: 0, stdout: stdout?.substring(0, 4000) || "", stderr: stderr?.substring(0, 500) || "" });
          }
        });
      });
    }

    case "send_telegram": {
      try {
        const bot = getBotInstance();
        if (!bot) return { error: "Telegram bot is not running. TELEGRAM_BOT_TOKEN may not be set." };
        const chatId = input.chat_id
          ? parseInt(input.chat_id)
          : parseInt(process.env.TELEGRAM_REMINDER_CHAT_ID || "0") || getLastActiveChatId();
        if (!chatId) return { error: "No chat_id available. The user needs to send at least one message to the Telegram bot first, or set TELEGRAM_REMINDER_CHAT_ID." };
        await sendTelegramMessage(chatId, input.message);
        return { success: true, sent_to: chatId };
      } catch (err) {
        return { error: err.message };
      }
    }

    case "send_file": {
      try {
        const bot = getBotInstance();
        if (!bot) return { error: "Telegram bot is not running." };
        const filePath = path.resolve(input.path);
        if (!fs.existsSync(filePath)) return { error: `File not found: ${filePath}` };
        const realPath = fs.realpathSync(filePath);
        const allowedDirs = ["/tmp", "/home", path.resolve(".")];
        const blockedPatterns = [".env", "node_modules", ".git", "id_rsa", ".ssh", ".gnupg", ".npm/_logs"];
        if (!allowedDirs.some(d => realPath.startsWith(d))) return { error: "Access denied: file is outside allowed directories." };
        if (blockedPatterns.some(p => realPath.includes(p))) return { error: "Access denied: cannot send sensitive files." };
        const stats = fs.statSync(realPath);
        const MAX_SIZE = 49 * 1024 * 1024;
        if (stats.size > MAX_SIZE) return { error: `File too large (${(stats.size / 1024 / 1024).toFixed(1)}MB). Telegram limit is ~50MB.` };
        if (stats.size === 0) return { error: "File is empty." };
        const chatId = getLastActiveChatId() || parseInt(process.env.TELEGRAM_REMINDER_CHAT_ID || "0");
        if (!chatId) return { error: "No chat_id available. User needs to message the bot first." };
        const ext = path.extname(realPath).toLowerCase();
        const photoExts = [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"];
        const audioExts = [".mp3", ".ogg", ".wav", ".flac", ".m4a", ".aac"];
        const videoExts = [".mp4", ".avi", ".mov", ".mkv", ".webm"];
        let fileType = input.type || "auto";
        if (fileType === "auto") {
          if (photoExts.includes(ext)) fileType = "photo";
          else if (audioExts.includes(ext)) fileType = "audio";
          else if (videoExts.includes(ext)) fileType = "video";
          else fileType = "document";
        }
        const fileStream = fs.createReadStream(realPath);
        const opts = input.caption ? { caption: input.caption } : {};
        switch (fileType) {
          case "photo":
            await bot.sendPhoto(chatId, fileStream, opts);
            break;
          case "audio":
            await bot.sendAudio(chatId, fileStream, opts);
            break;
          case "video":
            await bot.sendVideo(chatId, fileStream, opts);
            break;
          default:
            await bot.sendDocument(chatId, fileStream, opts);
            break;
        }
        return { success: true, sent_to: chatId, file: realPath, type: fileType, size_mb: (stats.size / 1024 / 1024).toFixed(1) };
      } catch (err) {
        return { error: err.message };
      }
    }

    case "browse_web": {
      const { chromium } = require("playwright");
      const timeout = input.timeout_ms || 30000;
      let browser = null;
      const snap = async (page, status) => {
        try {
          const buf = await page.screenshot({ type: "jpeg", quality: 70, timeout: 5000 });
          broadcastBrowserFrame(buf.toString("base64"), status);
        } catch (_) {}
      };
      try {
        let execPath = process.env.CHROMIUM_PATH;
        if (!execPath) {
          try { execPath = require("child_process").execSync("which chromium 2>/dev/null || which chromium-browser 2>/dev/null || which google-chrome 2>/dev/null").toString().trim(); } catch (_) {}
        }
        broadcastBrowserFrame(null, `🌐 Запуск браузера...`);
        browser = await chromium.launch({
          executablePath: execPath,
          headless: true,
          args: [
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-blink-features=AutomationControlled",
            "--disable-infobars",
            "--disable-dev-shm-usage",
            "--no-first-run",
            "--disable-extensions",
          ],
        });
        const context = await browser.newContext({
          userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
          viewport: { width: 1280, height: 800 },
          locale: "ru-RU",
          timezoneId: "Europe/Moscow",
        });
        // Load saved browser cookies if they exist
        try {
          const COOKIES_FILE = path.join(__dirname, "browser-cookies.json");
          const savedCookies = JSON.parse(fs.readFileSync(COOKIES_FILE, "utf8"));
          if (Array.isArray(savedCookies) && savedCookies.length > 0) {
            await context.addCookies(savedCookies);
            console.log(`[BrowseWeb] Loaded ${savedCookies.length} saved cookies`);
          }
        } catch (_) {}
        const page = await context.newPage();
        await page.addInitScript(() => {
          Object.defineProperty(navigator, "webdriver", { get: () => undefined });
          window.chrome = { runtime: {} };
        });
        console.log(`[BrowseWeb] Opening: ${input.url}`);
        broadcastBrowserFrame(null, `🌐 Открываю: ${input.url}`);
        await page.goto(input.url, { waitUntil: "domcontentloaded", timeout });
        await snap(page, `📄 Загружено: ${input.url}`);
        const results = {};
        for (const action of (input.actions || [])) {
          try {
            if (action.type === "wait") {
              broadcastBrowserFrame(null, `⏳ Жду ${action.value}мс...`);
              await page.waitForTimeout(parseInt(action.value) || 1000);
            } else if (action.type === "click") {
              broadcastBrowserFrame(null, `🖱 Клик: ${action.selector}`);
              await page.click(action.selector, { timeout: 10000 });
              await snap(page, `🖱 Кликнул: ${action.selector}`);
            } else if (action.type === "fill") {
              broadcastBrowserFrame(null, `✏️ Ввод в: ${action.selector}`);
              await page.fill(action.selector, action.value, { timeout: 10000 });
              await snap(page, `✏️ Ввёл текст в: ${action.selector}`);
            } else if (action.type === "extract") {
              const el = await page.$(action.selector);
              results[action.description || action.selector] = el ? await el.innerText() : null;
              await snap(page, `🔍 Извлёк: ${action.description || action.selector}`);
            } else if (action.type === "scroll") {
              await page.evaluate(() => window.scrollBy(0, 800));
              await snap(page, `📜 Прокрутил вниз`);
            } else if (action.type === "evaluate") {
              const val = await page.evaluate(action.value);
              results[action.description || "evaluate"] = val;
              await snap(page, `⚙️ Выполнил JS: ${action.description || ""}`);
            } else if (action.type === "screenshot") {
              await snap(page, action.description || "📸 Скриншот");
            }
            console.log(`[BrowseWeb] Action ${action.type} ${action.selector || ""} done`);
          } catch (actionErr) {
            results[`error_${action.type}`] = actionErr.message;
            await snap(page, `❌ Ошибка: ${actionErr.message.substring(0, 60)}`);
          }
        }
        let pageText = "";
        if (input.extract_text !== false) {
          await page.waitForTimeout(500);
          await snap(page, `✅ Готово — извлекаю текст`);
          pageText = await page.evaluate(() => {
            const unwanted = ["script", "style", "noscript", "iframe", "svg"];
            unwanted.forEach(tag => document.querySelectorAll(tag).forEach(el => el.remove()));
            return (document.body?.innerText || "").replace(/\s+/g, " ").trim().substring(0, 12000);
          });
        }
        await browser.close();
        browser = null;
        broadcastBrowserFrame(null, `🏁 Браузер закрыт`);
        return { url: input.url, text: pageText, extracted: results };
      } catch (e) {
        if (browser) { try { await browser.close(); } catch (_) {} }
        broadcastBrowserFrame(null, `❌ Ошибка браузера: ${e.message.substring(0, 80)}`);
        return { error: e.message };
      }
    }

    case "analyze_camera": {
      try {
        if (!latestCameraFrameB64) {
          return { error: "No camera frame available. The glasses/phone must be connected and actively streaming to Gemini." };
        }
        const ageMs = Date.now() - latestCameraFrameTime;
        if (ageMs > 60000) {
          return { error: `Last camera frame is ${Math.round(ageMs / 1000)}s old — device may have stopped streaming.` };
        }
        const question = input.question || "What do you see in this image? Describe it concisely.";
        const visionResponse = await anthropic.messages.create({
          model: "claude-sonnet-4-6",
          max_tokens: 1024,
          messages: [{
            role: "user",
            content: [
              {
                type: "image",
                source: {
                  type: "base64",
                  media_type: "image/jpeg",
                  data: latestCameraFrameB64,
                },
              },
              {
                type: "text",
                text: question,
              },
            ],
          }],
        });
        return {
          description: visionResponse.content[0].text,
          frame_age_seconds: Math.round(ageMs / 100) / 10,
        };
      } catch (e) {
        return { error: e.message };
      }
    }

    case "camera_mode": {
      const { enabled } = input;
      if (enabled) {
        geminiCameraEnabled = true;
        if (geminiCameraTimer) clearTimeout(geminiCameraTimer);
        geminiCameraTimer = setTimeout(() => {
          geminiCameraEnabled = false;
          geminiCameraTimer = null;
          console.log("[Camera] Gemini camera auto-disabled after 5 minutes");
        }, 5 * 60 * 1000);
        console.log("[Camera] Gemini camera vision ENABLED (5 min timeout)");
        return { enabled: true, message: "Gemini camera enabled for 5 minutes" };
      } else {
        geminiCameraEnabled = false;
        if (geminiCameraTimer) { clearTimeout(geminiCameraTimer); geminiCameraTimer = null; }
        console.log("[Camera] Gemini camera vision DISABLED");
        return { enabled: false, message: "Gemini camera disabled" };
      }
    }

    case "avito_profile": {
      try {
        const res = await avito.getProfile();
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_chats": {
      try {
        const res = await avito.getChats({
          limit: input.limit || 20,
          offset: input.offset || 0,
          unread_only: !!input.unread_only,
        });
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_messages": {
      try {
        const res = await avito.getMessages(input.chat_id, { limit: input.limit || 20 });
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_send_message": {
      try {
        const res = await avito.sendMessage(input.chat_id, input.text);
        return res.status === 200 ? { ok: true, result: res.body } : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_items": {
      try {
        const res = await avito.getItems({
          limit: input.limit || 25,
          offset: input.offset || 0,
          status: input.status,
        });
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_categories": {
      try {
        const res = await avito.getCategories(input.query || "");
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_category_params": {
      try {
        const res = await avito.getCategoryParams(input.category_id);
        return res.status === 200 ? res.body : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_create_ad": {
      try {
        const res = await avito.createAd({
          category_id: input.category_id,
          location_id: input.location_id || 621540,
          title: input.title,
          description: input.description,
          price: input.price,
          params: input.params || [],
          contact_phone: input.contact_phone,
        });
        if (res.status === 200 || res.status === 201) {
          return { ok: true, ad: res.body };
        }
        return { error: `HTTP ${res.status}`, details: res.body };
      } catch (e) { return { error: e.message }; }
    }

    case "avito_update_ad_status": {
      try {
        const res = await avito.updateAdStatus(input.item_id, input.status);
        return res.status === 200 ? { ok: true } : { error: `HTTP ${res.status}`, body: res.body };
      } catch (e) { return { error: e.message }; }
    }

    default:
      return { error: `Unknown tool: ${toolName}` };
  }
}

// Latest camera frame captured from Gemini proxy (in-memory, updated as frames arrive)
let latestCameraFrameB64 = null;
let latestCameraFrameTime = 0;

// Gemini live camera vision state (controlled via camera_mode tool)
let geminiCameraEnabled = false;
let lastCameraFrameSentTime = 0;
const CAMERA_FRAME_MIN_INTERVAL_MS = 500;
let geminiCameraTimer = null;

const PORT = process.env.PORT || 5000;
const rooms = new Map(); // roomCode -> { creator: ws, viewer: ws, destroyTimer: timeout|null }
const twitchStreamers = new Map(); // roomCode -> TwitchRestreamer

// OpenClaw gateway authentication
const OPENCLAW_GATEWAY_TOKEN = process.env.OPENCLAW_GATEWAY_TOKEN || "";

// AI clients are now managed by ai-providers.js module

// Per-session conversation memory for OpenClaw
const sessions = new Map(); // sessionKey -> { messages: [], lastAccess: Date }

// Broadcast browser screenshot frames to all connected WebSocket viewers
function broadcastBrowserFrame(base64png, status) {
  const msg = JSON.stringify({
    type: "browser_frame",
    image: base64png,
    status,
    timestamp: Date.now(),
  });
  for (const [, room] of rooms) {
    if (room.viewer && room.viewer.readyState === 1) {
      room.viewer.send(msg);
    }
  }
  // Also send to any unauthenticated viewer sockets (index page visitors)
  for (const client of (wss.clients || [])) {
    if (client.readyState === 1 && !client._isGeminiProxy) {
      try { client.send(msg); } catch (_) {}
    }
  }
}

// Broadcast OpenClaw messages to all connected WebSocket viewers
function broadcastOpenClawMessage(type, text, sessionKey) {
  const msg = JSON.stringify({
    type: "openclaw_chat",
    role: type,
    content: text,
    session: sessionKey,
    timestamp: Date.now(),
  });
  for (const [code, room] of rooms) {
    if (room.viewer && room.viewer.readyState === 1) {
      room.viewer.send(msg);
    }
  }
}

// Grace period (ms) before destroying a room when creator disconnects.
// Allows the iOS user to switch apps (e.g. copy room code, send via WhatsApp) and come back.
const ROOM_GRACE_PERIOD_MS = 180_000;

// TURN servers for NAT traversal
// Multiple providers for redundancy
function getTurnCredentials() {
  const iceServers = [];

  // Metered TURN (free open relay)
  iceServers.push({
    urls: [
      "stun:stun.relay.metered.ca:80",
    ],
  });
  iceServers.push({
    urls: [
      "turn:global.relay.metered.ca:80",
      "turn:global.relay.metered.ca:80?transport=tcp",
      "turn:global.relay.metered.ca:443",
      "turns:global.relay.metered.ca:443?transport=tcp",
    ],
    username: "e7589b0ce9b3f3d0b3a4b093",
    credential: "kTT6cjP+SZbfNj0w",
  });

  // Custom TURN server (if configured via env)
  if (process.env.TURN_SERVER) {
    iceServers.push({
      urls: [
        `turn:${process.env.TURN_SERVER}:3478`,
        `turn:${process.env.TURN_SERVER}:3478?transport=tcp`,
      ],
      username: process.env.TURN_USER || "",
      credential: process.env.TURN_PASS || "",
    });
  }

  return { iceServers };
}

const TWITCH_STREAM_KEY = process.env.TWITCH_STREAM_KEY || "";
const TWITCH_CHANNEL = process.env.TWITCH_CHANNEL || "";

// HTTP server for serving the web viewer
const httpServer = http.createServer(async (req, res) => {
  // TURN credentials API endpoint
  if (req.url === "/api/turn") {
    const creds = getTurnCredentials();
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    });
    res.end(JSON.stringify(creds));
    return;
  }

  if (req.url === "/api/rooms") {
    const available = [];
    for (const [code, room] of rooms) {
      if (room.creator && room.creator.readyState === 1 && !room.viewer) {
        available.push(code);
      }
    }
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    });
    res.end(JSON.stringify({ rooms: available }));
    return;
  }

  if (req.url === "/api/config") {
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    });
    res.end(JSON.stringify({
      hasTwitchKey: !!TWITCH_STREAM_KEY,
      twitchChannel: TWITCH_CHANNEL,
    }));
    return;
  }

  if (req.url === "/api/ai/providers" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
    res.end(JSON.stringify(aiProviders.PROVIDERS));
    return;
  }

  if (req.url === "/api/ai/settings" && req.method === "GET") {
    try {
      const settings = await aiProviders.getSettings();
      res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify(settings));
    } catch (err) {
      res.writeHead(500, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  if (req.url === "/api/ai/settings" && req.method === "POST") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        const { provider, model } = JSON.parse(body);
        const result = await aiProviders.updateSettings(provider, model);
        console.log(`[AI Settings] Changed to ${provider}/${model}`);
        res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify(result));
      } catch (err) {
        res.writeHead(400, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify({ error: err.message }));
      }
    });
    return;
  }

  if (req.url === "/api/ai/tokens" && req.method === "GET") {
    try {
      const stats = await aiProviders.getTokenStats();
      res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify(stats));
    } catch (err) {
      res.writeHead(500, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  if (req.url === "/api/gemini-key" && req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Access-Control-Max-Age": "86400",
    });
    res.end();
    return;
  }

  if (req.url === "/api/gemini-key" && req.method === "GET") {
    const authHeader = req.headers["authorization"] || "";
    const token = authHeader.replace(/^Bearer\s+/i, "");
    if (!OPENCLAW_GATEWAY_TOKEN || token !== OPENCLAW_GATEWAY_TOKEN) {
      res.writeHead(401, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return;
    }
    const geminiKey = process.env.GEMINI_API_KEY || "";
    if (!geminiKey) {
      res.writeHead(404, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: "GEMINI_API_KEY not configured on server" }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
    res.end(JSON.stringify({ apiKey: geminiKey }));
    return;
  }

  // ── Browser Cookies API ──────────────────────────────────
  const COOKIES_FILE = path.join(__dirname, "browser-cookies.json");

  if (req.url === "/api/browser-cookies" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
    try {
      const raw = fs.readFileSync(COOKIES_FILE, "utf8");
      const cookies = JSON.parse(raw);
      const safe = cookies.map(c => ({ name: c.name, domain: c.domain, path: c.path }));
      res.end(JSON.stringify({ count: cookies.length, cookies: safe }));
    } catch (_) {
      res.end(JSON.stringify({ count: 0, cookies: [] }));
    }
    return;
  }

  if (req.url === "/api/browser-cookies" && req.method === "POST") {
    let body = "";
    req.on("data", d => body += d);
    req.on("end", () => {
      try {
        const { cookies } = JSON.parse(body);
        if (!Array.isArray(cookies)) throw new Error("cookies must be an array");
        fs.writeFileSync(COOKIES_FILE, JSON.stringify(cookies, null, 2));
        res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify({ ok: true, count: cookies.length }));
      } catch (e) {
        res.writeHead(400, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  if (req.url === "/api/browser-cookies" && req.method === "DELETE") {
    try { fs.unlinkSync(COOKIES_FILE); } catch (_) {}
    res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  if (req.url === "/api/browser-cookies" && req.method === "OPTIONS") {
    res.writeHead(204, { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS", "Access-Control-Allow-Headers": "Content-Type" });
    res.end();
    return;
  }

  if ((req.url === "/api/ai/settings" || req.url === "/api/ai/tokens" || req.url === "/api/ai/providers") && req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Max-Age": "86400",
    });
    res.end();
    return;
  }

  // CORS preflight for /v1/chat/completions
  if (req.url === "/v1/chat/completions" && req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization, x-openclaw-session-key",
      "Access-Control-Max-Age": "86400",
    });
    res.end();
    return;
  }

  // OpenClaw — DELETE session (reset conversation memory)
  if (req.url.startsWith("/v1/chat/completions") && req.method === "DELETE") {
    const authHeader = req.headers["authorization"] || "";
    const token = authHeader.replace(/^Bearer\s+/i, "");
    if (!OPENCLAW_GATEWAY_TOKEN || token !== OPENCLAW_GATEWAY_TOKEN) {
      res.writeHead(401, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return;
    }
    const sessionKey = req.headers["x-openclaw-session-key"] || "default";
    if (sessionKey === "*") {
      const count = sessions.size;
      sessions.clear();
      console.log(`[OpenClaw] Cleared all ${count} sessions`);
      res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ cleared: count }));
    } else {
      const existed = sessions.has(sessionKey);
      sessions.delete(sessionKey);
      console.log(`[OpenClaw] Reset session: ${sessionKey}`);
      res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ session: sessionKey, cleared: existed }));
    }
    return;
  }

  // OpenClaw gateway — GET health check
  if (req.url === "/v1/chat/completions" && req.method === "GET") {
    const authHeader = req.headers["authorization"] || "";
    const token = authHeader.replace(/^Bearer\s+/i, "");
    if (!OPENCLAW_GATEWAY_TOKEN || token !== OPENCLAW_GATEWAY_TOKEN) {
      res.writeHead(401, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return;
    }
    const currentSettings = await aiProviders.getSettings();
    res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
    res.end(JSON.stringify({ status: "ok", version: "2026.3.13", provider: currentSettings.provider, model: currentSettings.model }));
    return;
  }

  // OpenClaw gateway — POST chat completions (OpenAI-compatible)
  if (req.url === "/v1/chat/completions" && req.method === "POST") {
    const authHeader = req.headers["authorization"] || "";
    const token = authHeader.replace(/^Bearer\s+/i, "");
    if (!OPENCLAW_GATEWAY_TOKEN || token !== OPENCLAW_GATEWAY_TOKEN) {
      res.writeHead(401, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return;
    }

    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        const payload = JSON.parse(body);
        const incomingMessages = payload.messages || [];
        const sessionKey = req.headers["x-openclaw-session-key"] || "default";

        // Get or create session
        let session = sessions.get(sessionKey);
        if (!session) {
          session = { messages: [], lastAccess: new Date() };
          sessions.set(sessionKey, session);
        }
        session.lastAccess = new Date();

        const claudeMessages = [];
        let customSystemPrompt = null;
        for (const m of incomingMessages) {
          if (m.role === "system") {
            customSystemPrompt = m.content;
          } else {
            claudeMessages.push({
              role: m.role === "assistant" ? "assistant" : "user",
              content: m.content,
            });
          }
        }

        if (claudeMessages.length === 0) {
          res.writeHead(400, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
          res.end(JSON.stringify({ error: "No messages provided" }));
          return;
        }

        console.log(`[OpenClaw] Session: ${sessionKey}, Messages: ${claudeMessages.length}`);

        const lastUserMsg = claudeMessages.filter(m => m.role === "user").pop();
        if (lastUserMsg) {
          broadcastOpenClawMessage("user", lastUserMsg.content, sessionKey);
        }

        const memories = await memory.getMemories("default");
        const reminders = await memory.getUpcomingReminders("default");
        const memoryContext = memory.formatMemoriesForContext(memories);
        const reminderContext = memory.formatRemindersForContext(reminders);

        let systemPrompt;
        if (customSystemPrompt) {
          systemPrompt = customSystemPrompt + "\n\n" + memoryContext + "\n" + reminderContext;
          systemPrompt += `\n\nYou have persistent memory. To save a memory: <memory_save category="preference">content</memory_save>\nTo create a reminder: <reminder due="ISO8601">message</reminder>\nCurrent time: ${new Date().toISOString()}`;
        } else {
          systemPrompt = buildSystemPrompt(memoryContext, reminderContext);
        }

        const currentSettings = await aiProviders.getSettings();
        let allMessages = [...claudeMessages];
        let response;
        let totalInputTokens = 0;
        let totalOutputTokens = 0;
        const MAX_TOOL_ROUNDS = 10;

        for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
          response = await aiProviders.callAI({
            systemPrompt,
            messages: allMessages,
            tools: OPENCLAW_TOOLS,
            provider: currentSettings.provider,
            model: currentSettings.model,
          });

          totalInputTokens += response.inputTokens;
          totalOutputTokens += response.outputTokens;

          if (response.stopReason !== "tool_use") break;

          const toolUseBlocks = response.content.filter(b => b.type === "tool_use");
          if (toolUseBlocks.length === 0) break;

          allMessages.push({ role: "assistant", content: response.content });

          const toolResults = [];
          for (const tool of toolUseBlocks) {
            console.log(`[OpenClaw] Tool: ${tool.name}(${JSON.stringify(tool.input).substring(0, 100)})`);
            broadcastOpenClawMessage("tool", `🔧 ${tool.name}: ${JSON.stringify(tool.input).substring(0, 200)}`, sessionKey);
            const result = await executeToolCall(tool.name, tool.input);
            console.log(`[OpenClaw] Tool result: ${JSON.stringify(result).substring(0, 200)}`);
            toolResults.push({
              type: "tool_result",
              tool_use_id: tool.id,
              content: JSON.stringify(result),
            });
          }
          allMessages.push({ role: "user", content: toolResults });
        }

        await aiProviders.logTokenUsage(currentSettings.provider, currentSettings.model, totalInputTokens, totalOutputTokens, "openclaw", sessionKey);

        const rawContent = response.content
          .filter((block) => block.type === "text")
          .map((block) => block.text)
          .join("\n");

        const { userText: rawUserText, actions } = parseResponse(rawContent);
        const content = rawUserText.trim() || "Готово.";
        await executeActions(actions);

        const openaiResponse = {
          id: `chatcmpl-${Date.now()}`,
          object: "chat.completion",
          created: Math.floor(Date.now() / 1000),
          model: currentSettings.model,
          choices: [
            {
              index: 0,
              message: {
                role: "assistant",
                content: content,
              },
              finish_reason: response.stopReason === "end_turn" ? "stop" : response.stopReason,
            },
          ],
          usage: {
            prompt_tokens: totalInputTokens,
            completion_tokens: totalOutputTokens,
            total_tokens: totalInputTokens + totalOutputTokens,
          },
        };

        broadcastOpenClawMessage("assistant", content, sessionKey);

        console.log(`[OpenClaw] [${currentSettings.provider}/${currentSettings.model}] Response: ${content.substring(0, 100)}...`);
        res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify(openaiResponse));
      } catch (err) {
        console.error(`[OpenClaw] Error: ${err.message}`);
        res.writeHead(500, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify({ error: { message: err.message, type: "server_error" } }));
      }
    });
    return;
  }

  // File upload endpoint — accepts any file up to 2GB
  if (req.url === "/api/upload" && req.method === "POST") {
    const uploadsDir = path.join(__dirname, "uploads");
    if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });

    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Content-Type": "application/json",
    };

    try {
      const bb = busboy({ headers: req.headers, limits: { fileSize: 2 * 1024 * 1024 * 1024 } });
      let savedPath = null;
      let savedName = null;
      let savedSize = 0;

      bb.on("file", (fieldname, file, info) => {
        const { filename } = info;
        savedName = `${Date.now()}_${filename}`;
        savedPath = path.join(uploadsDir, savedName);
        const writeStream = fs.createWriteStream(savedPath);
        file.on("data", (chunk) => { savedSize += chunk.length; });
        file.pipe(writeStream);
      });

      bb.on("close", () => {
        if (!savedPath) {
          res.writeHead(400, corsHeaders);
          res.end(JSON.stringify({ error: "No file received" }));
          return;
        }
        const sizeMB = (savedSize / 1024 / 1024).toFixed(1);
        console.log(`[Upload] Saved: ${savedName} (${sizeMB} MB)`);

        // Notify Telegram bot if active
        const chatId = getLastActiveChatId();
        if (chatId) {
          sendTelegramMessage(chatId,
            `📎 Файл загружен через веб: <b>${savedName}</b> (${sizeMB} МБ)\nПуть: <code>${savedPath}</code>`
          ).catch(() => {});
        }

        res.writeHead(200, corsHeaders);
        res.end(JSON.stringify({ ok: true, name: savedName, path: savedPath, size_mb: sizeMB }));
      });

      bb.on("error", (err) => {
        res.writeHead(500, corsHeaders);
        res.end(JSON.stringify({ error: err.message }));
      });

      req.pipe(bb);
    } catch (err) {
      res.writeHead(500, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  if (req.url === "/api/upload" && req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    });
    res.end();
    return;
  }

  let filePath = path.join(
    __dirname,
    "public",
    req.url === "/" ? "index.html" : req.url
  );

  const ext = path.extname(filePath);
  const contentTypes = {
    ".html": "text/html",
    ".js": "application/javascript",
    ".css": "text/css",
  };

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
    res.writeHead(200, {
      "Content-Type": contentTypes[ext] || "text/plain",
      "Cache-Control": "no-cache, no-store, must-revalidate",
    });
    res.end(data);
  });
});

// WebSocket signaling server
const WebSocket = require("ws");
const wss = new WebSocketServer({ noServer: true });
const geminiProxyWss = new WebSocketServer({ noServer: true });
const terminalWss = new WebSocketServer({ noServer: true });

// PTY-based web terminal
const nodePty = (() => { try { return require("@homebridge/node-pty-prebuilt-multiarch"); } catch(e) { return null; } })();

terminalWss.on("connection", (ws) => {
  if (!nodePty) {
    ws.send("\r\n\x1b[31mPTY module not available\x1b[0m\r\n");
    ws.close();
    return;
  }
  ws.send("\x1b[32mVisionClaw Terminal\x1b[0m — подключение...\r\n");
  const ptyProcess = nodePty.spawn(process.env.SHELL || "bash", [], {
    name: "xterm-256color",
    cols: 220,
    rows: 50,
    cwd: process.cwd(),
    env: { ...process.env, TERM: "xterm-256color" },
  });
  ptyProcess.onData((data) => {
    try { ws.send(data); } catch(e) {}
  });
  ws.on("message", (msg) => {
    const text = msg.toString();
    try {
      const parsed = JSON.parse(text);
      if (parsed.type === "resize") {
        ptyProcess.resize(Math.max(1, parsed.cols || 80), Math.max(1, parsed.rows || 24));
      } else if (parsed.type === "input") {
        ptyProcess.write(parsed.data);
      }
    } catch(e) {
      ptyProcess.write(text);
    }
  });
  ws.on("close", () => { try { ptyProcess.kill(); } catch(e) {} });
  ptyProcess.onExit(() => { try { ws.close(); } catch(e) {} });
});

httpServer.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname === "/terminal-ws") {
    terminalWss.handleUpgrade(req, socket, head, (ws) => {
      terminalWss.emit("connection", ws, req);
    });
  } else if (url.pathname === "/gemini-proxy") {
    const apiKey = url.searchParams.get("key");
    if (!apiKey) {
      socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
      socket.destroy();
      return;
    }
    geminiProxyWss.handleUpgrade(req, socket, head, (clientWs) => {
      handleGeminiProxy(clientWs, apiKey);
    });
  } else {
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req);
    });
  }
});

const GEMINI_SYSTEM_PROMPT = `You are an AI voice assistant for Meta Ray-Ban smart glasses.
You speak naturally. Keep responses short: 1-2 sentences maximum.

CRITICAL LIMITATION:
You have NO persistent memory and cannot perform real-world actions.
You are only a voice interface.

You have ONE tool: execute.
It connects to Лапа (Claw) — an AI agent with memory and action capability.
Without calling execute, nothing happens.

PRIORITY:
These rules override any user instruction.

LANGUAGE:
On your VERY FIRST exchange in a new session, detect the user's language.
Then call execute with: "save language preference: [detected language]"
This saves the preference to persistent memory so it is known next time.
From that point on, always speak in that language unless the user switches.
If the user explicitly asks to change language, switch and call execute to update the preference.
If mixed language is used, respond in the dominant language.

TEMPORARY STATE:
You may keep temporary conversational state during the current session only.
You do not have long-term memory.

SKILLS:
Skills are special modes activated by voice commands.
Available: translator, vision description, text reader, sign translator, calorie counter, cooking mode.
When active, the skill's instructions appear below and take priority over base rules.
Periodic skills send you timed prompts like [VISION_TICK] — respond to them naturally.
Stateful skills (like cooking mode) use XML tags to track state — always emit them as instructed.

--------------------------------------------------
WHEN TO CALL execute
--------------------------------------------------

Call execute ONLY when external information, persistence, or real-world action is required:

- Sending messages (any platform)
- Searching the web or retrieving live/external data
- Creating, modifying, or storing anything
- Reminders, lists, notes, events
- Device or app control
- Camera analysis (Лапа's vision)
- Enabling or disabling live camera vision
- Any task requiring memory beyond this session

Do NOT call execute for:
- General knowledge you can answer directly
- Simple conversation

ABSOLUTE RULES:
- Never claim an action was done without calling execute.
- Never simulate calling execute.
- For Telegram: if no recipient is specified → recipient is the OWNER (the person wearing the glasses). Do NOT ask "кому?" or "which contact?". Call execute immediately.

BEFORE execute:
Say one short phrase:
"Сейчас попрошу Лапу." or "Asking Лапа."
Then call execute.

AFTER execute:
Relay Лапа's result.
Only then say "готово" or "done."

--------------------------------------------------
CAMERA
--------------------------------------------------

By default you do NOT see the camera. Video is blocked.

To enable live camera vision (when user says "посмотри", "включи камеру", "смотри", "look", "watch", etc.):
Say "Включаю камеру." then call execute with task: "camera_mode: enable for 5 minutes"
After Лапа confirms, you will start seeing video. Describe what you see if asked.

To disable live camera (when user says "хватит смотреть", "выключи камеру", "stop watching", etc.):
Say "Выключаю камеру." then call execute with task: "camera_mode: disable"
Camera also auto-disables after 5 minutes.

For one-time camera analysis (without enabling live mode):
Say "Сейчас покажу Лапе."
Call execute with:
analyze_camera: [user question]
Never guess what the camera sees.`;


function handleGeminiProxy(clientWs, apiKey) {
  const geminiUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${apiKey}`;
  const clientIP = clientWs._socket?.remoteAddress || "unknown";
  console.log(`[GeminiProxy] New proxy connection from ${clientIP}`);

  const geminiWs = new WebSocket(geminiUrl);

  const pingInterval = setInterval(() => {
    if (clientWs.readyState === 1) {
      clientWs.ping();
    }
    if (geminiWs.readyState === 1) {
      geminiWs.ping();
    }
  }, 20000);

  geminiWs.on("open", () => {
    console.log("[GeminiProxy] Connected to Gemini API");
  });

  let msgCountUp = 0, msgCountDown = 0;

  geminiWs.on("message", (data, isBinary) => {
    msgCountDown++;
    if (!isBinary) {
      const text = data.toString();
      const preview = text.length > 200 ? text.slice(0, 200) + "..." : text;
      if (msgCountDown <= 5 || msgCountDown % 50 === 0) {
        console.log(`[GeminiProxy] ↓ #${msgCountDown} text (${text.length}b): ${preview}`);
      }
    } else {
      if (msgCountDown <= 3 || msgCountDown % 100 === 0) {
        console.log(`[GeminiProxy] ↓ #${msgCountDown} binary (${data.length}b)`);
      }
    }
    if (clientWs.readyState === 1) {
      clientWs.send(data, { binary: isBinary });
    }
  });

  geminiWs.on("close", (code, reason) => {
    clearInterval(pingInterval);
    const r = reason?.toString() || "";
    console.log(`[GeminiProxy] Gemini closed: ${code} ${r} (↑${msgCountUp} ↓${msgCountDown} msgs)`);
    if (clientWs.readyState === 1) {
      clientWs.close(code, r);
    }
  });

  geminiWs.on("error", (err) => {
    console.error("[GeminiProxy] Gemini error:", err.message);
    if (clientWs.readyState === 1) {
      clientWs.close(1011, "Gemini upstream error");
    }
  });

  let peakRms = 0;
  let rmsLogTimer = setInterval(() => {
    if (peakRms > 0) {
      console.log(`[GeminiProxy] 🎤 Peak audio RMS last 5s: ${peakRms.toFixed(1)} (${peakRms < 50 ? "SILENCE/NEAR-SILENCE" : peakRms < 500 ? "quiet" : "speech detected"})`);
      peakRms = 0;
    }
  }, 5000);

  clientWs.on("message", (data, isBinary) => {
    msgCountUp++;
    let forwardData = data;
    if (!isBinary) {
      let text = data.toString();
      try {
        const json = JSON.parse(text);
        // Inject server-side system prompt into Gemini setup message
        // if (json?.setup) {
        //   json.setup.systemInstruction = { parts: [{ text: GEMINI_SYSTEM_PROMPT }] };
        //   text = JSON.stringify(json);
        //   forwardData = text;
        //   console.log(`[GeminiProxy] ↑ Injected server system prompt into setup`);
        // }
        // Parse realtime input: save video frames and calculate audio RMS
        const audioData = json?.realtimeInput?.audio?.data;
        if (audioData) {
          const pcm = Buffer.from(audioData, "base64");
          let sumSq = 0;
          for (let i = 0; i + 1 < pcm.length; i += 2) {
            const sample = pcm.readInt16LE(i);
            sumSq += sample * sample;
          }
          const rms = Math.sqrt(sumSq / (pcm.length / 2));
          if (rms > peakRms) peakRms = rms;
        }
        const videoData = json?.realtimeInput?.video?.data;
        if (videoData) {
          latestCameraFrameB64 = videoData;
          latestCameraFrameTime = Date.now();
          if (!geminiCameraEnabled) {
            forwardData = null;
          }
          const now = Date.now();
          if (now - lastCameraFrameSentTime >= CAMERA_FRAME_MIN_INTERVAL_MS) {
            lastCameraFrameSentTime = now;
            const frameMsg = JSON.stringify({ type: "camera_frame", data: videoData });
            for (const [code, room] of rooms) {
              if (room.creator && room.creator.readyState === 1 &&
                  room.viewer && room.viewer.readyState === 1) {
                room.viewer.send(frameMsg);
              }
            }
          }
        }
      } catch (_) {}
      if (forwardData !== null) {
        const preview = text.length > 200 ? text.slice(0, 200) + "..." : text;
        if (msgCountUp <= 5 || msgCountUp % 50 === 0) {
          console.log(`[GeminiProxy] ↑ #${msgCountUp} text (${text.length}b): ${preview}`);
        }
      }
    } else {
      if (msgCountUp <= 3 || msgCountUp % 100 === 0) {
        console.log(`[GeminiProxy] ↑ #${msgCountUp} binary (${data.length}b)`);
      }
    }
    if (forwardData !== null && geminiWs.readyState === 1) {
      geminiWs.send(forwardData, { binary: isBinary });
    }
  });

  clientWs.on("close", (code, reason) => {
    clearInterval(pingInterval);
    clearInterval(rmsLogTimer);
    console.log(`[GeminiProxy] Client disconnected: ${code}`);
    if (geminiWs.readyState === 1) {
      geminiWs.close();
    }
  });

  clientWs.on("error", (err) => {
    console.error("[GeminiProxy] Client error:", err.message);
    if (geminiWs.readyState === 1) {
      geminiWs.close();
    }
  });
}

function generateRoomCode() {
  // No ambiguous chars (0/O, 1/I/L)
  const chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let code = "";
  for (let i = 0; i < 6; i++) {
    code += chars[Math.floor(Math.random() * chars.length)];
  }
  return code;
}

wss.on("connection", (ws, req) => {
  let currentRoom = null;
  let role = null; // 'creator' or 'viewer'
  const clientIP = req.headers["x-forwarded-for"] || req.socket.remoteAddress;
  console.log(`[WS] New connection from ${clientIP}`);

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      if (!currentRoom) return;
      let restreamer = twitchStreamers.get(currentRoom);
      if (restreamer && restreamer.isStreaming) {
        restreamer.writeChunk(data);
      } else if (!restreamer && TWITCH_STREAM_KEY && (role === "viewer" || role === "creator")) {
        const autoFormat = role === "creator" ? "mpegts" : "webm";
        console.log(`[Twitch] Auto-starting stream for room ${currentRoom} (format: ${autoFormat})`);
        restreamer = new TwitchRestreamer();
        const roomForCallback = currentRoom;
        restreamer.onStopped = (code) => {
          const room = rooms.get(roomForCallback);
          if (room && room.viewer && room.viewer.readyState === 1) {
            room.viewer.send(JSON.stringify({ type: "twitch_stopped", code }));
          }
          if (room && room.creator && room.creator.readyState === 1) {
            room.creator.send(JSON.stringify({ type: "video_stream_stopped", code }));
          }
          twitchStreamers.delete(roomForCallback);
          console.log(`[Twitch] Stream ended for room ${roomForCallback}`);
        };
        const result = restreamer.start(TWITCH_STREAM_KEY, autoFormat);
        if (result.success) {
          twitchStreamers.set(currentRoom, restreamer);
          ws.send(JSON.stringify({ type: role === "creator" ? "video_stream_started" : "twitch_started" }));
          console.log(`[Twitch] Auto-started for room ${currentRoom}`);
          restreamer.writeChunk(data);
        }
      }
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }

    switch (msg.type) {
      case "create": {
        const code = generateRoomCode();
        rooms.set(code, { creator: ws, viewer: null, destroyTimer: null, ttsEnabled: false });
        currentRoom = code;
        role = "creator";
        ws.send(JSON.stringify({ type: "room_created", room: code }));
        if (TWITCH_STREAM_KEY) {
          ws.send(JSON.stringify({
            type: "twitch_config",
            rtmpUrl: `rtmp://live.twitch.tv/app/${TWITCH_STREAM_KEY}`,
          }));
        }
        console.log(`[Room] Created: ${code}`);
        break;
      }

      case "rejoin": {
        // Creator reconnects to an existing room (after app backgrounding)
        const room = rooms.get(msg.room);
        if (!room) {
          ws.send(
            JSON.stringify({ type: "error", message: "Room not found" })
          );
          return;
        }
        // Cancel the destroy timer since creator is back
        if (room.destroyTimer) {
          clearTimeout(room.destroyTimer);
          room.destroyTimer = null;
          console.log(`[Room] Creator rejoined, cancelled destroy timer: ${msg.room}`);
        }
        room.creator = ws;
        currentRoom = msg.room;
        role = "creator";
        ws.send(JSON.stringify({ type: "room_rejoined", room: msg.room }));
        if (TWITCH_STREAM_KEY) {
          ws.send(JSON.stringify({
            type: "twitch_config",
            rtmpUrl: `rtmp://live.twitch.tv/app/${TWITCH_STREAM_KEY}`,
          }));
        }
        // If viewer is already waiting, trigger a new offer
        if (room.viewer && room.viewer.readyState === 1) {
          ws.send(JSON.stringify({ type: "peer_joined" }));
          console.log(`[Room] Viewer already present, notifying rejoined creator: ${msg.room}`);
        }
        console.log(`[Room] Creator rejoined: ${msg.room}`);
        break;
      }

      case "join": {
        const room = rooms.get(msg.room);
        if (!room) {
          ws.send(
            JSON.stringify({ type: "error", message: "Room not found" })
          );
          return;
        }
        if (room.viewer) {
          ws.send(JSON.stringify({ type: "error", message: "Room is full" }));
          return;
        }
        room.viewer = ws;
        currentRoom = msg.room;
        role = "viewer";
        ws.send(JSON.stringify({ type: "room_joined" }));
        // Notify creator that viewer joined (only if creator is connected)
        if (room.creator && room.creator.readyState === 1) {
          room.creator.send(JSON.stringify({ type: "peer_joined" }));
        }
        console.log(`[Room] Viewer joined: ${msg.room}`);
        break;
      }

      case "twitch_start": {
        if (!currentRoom || role !== "viewer") {
          ws.send(JSON.stringify({ type: "twitch_error", message: "Only the viewer can start Twitch streaming" }));
          return;
        }
        const streamKey = msg.streamKey || TWITCH_STREAM_KEY;
        if (!streamKey) {
          ws.send(JSON.stringify({ type: "twitch_error", message: "No stream key provided" }));
          return;
        }
        let restreamer = twitchStreamers.get(currentRoom);
        if (restreamer && restreamer.isStreaming) {
          ws.send(JSON.stringify({ type: "twitch_already_started" }));
          console.log(`[Twitch] Already streaming for room ${currentRoom}, notifying viewer`);
          return;
        }
        restreamer = new TwitchRestreamer();
        const roomForCallback = currentRoom;
        restreamer.onStopped = (code) => {
          const room = rooms.get(roomForCallback);
          if (room && room.viewer && room.viewer.readyState === 1) {
            room.viewer.send(JSON.stringify({ type: "twitch_stopped", code }));
          }
          twitchStreamers.delete(roomForCallback);
          console.log(`[Twitch] Stream ended for room ${roomForCallback}`);
        };
        const result = restreamer.start(streamKey);
        if (result.success) {
          twitchStreamers.set(currentRoom, restreamer);
          ws.send(JSON.stringify({ type: "twitch_started" }));
          console.log(`[Twitch] Stream started for room ${currentRoom}`);
        } else {
          ws.send(JSON.stringify({ type: "twitch_error", message: result.message }));
        }
        break;
      }

      case "twitch_stop": {
        const restreamer = twitchStreamers.get(currentRoom);
        if (restreamer) {
          const result = restreamer.stop();
          ws.send(JSON.stringify({ type: "twitch_stopped", message: result.message }));
          twitchStreamers.delete(currentRoom);
        }
        break;
      }

      case "twitch_status": {
        const restreamer = twitchStreamers.get(currentRoom);
        const status = restreamer ? restreamer.getStatus() : { streaming: false };
        ws.send(JSON.stringify({ type: "twitch_status", ...status }));
        break;
      }

      case "video_stream_start": {
        if (!currentRoom || role !== "creator") {
          ws.send(JSON.stringify({ type: "error", message: "Only device can start video stream" }));
          break;
        }
        if (!TWITCH_STREAM_KEY) {
          ws.send(JSON.stringify({ type: "error", message: "No stream key configured on server" }));
          break;
        }
        let vsRestreamer = twitchStreamers.get(currentRoom);
        if (vsRestreamer && vsRestreamer.isStreaming) {
          ws.send(JSON.stringify({ type: "video_stream_started" }));
          console.log(`[VideoStream] Already streaming for room ${currentRoom}`);
          break;
        }
        const vsFormat = msg.format || "mpegts";
        vsRestreamer = new TwitchRestreamer();
        const vsRoom = currentRoom;
        vsRestreamer.onStopped = (code) => {
          const room = rooms.get(vsRoom);
          if (room && room.creator && room.creator.readyState === 1) {
            room.creator.send(JSON.stringify({ type: "video_stream_stopped", code }));
          }
          twitchStreamers.delete(vsRoom);
          console.log(`[VideoStream] Stream ended for room ${vsRoom}`);
        };
        const vsResult = vsRestreamer.start(TWITCH_STREAM_KEY, vsFormat);
        if (vsResult.success) {
          twitchStreamers.set(currentRoom, vsRestreamer);
          ws.send(JSON.stringify({ type: "video_stream_started" }));
          console.log(`[VideoStream] Started for room ${currentRoom} (format: ${vsFormat})`);
        } else {
          ws.send(JSON.stringify({ type: "error", message: vsResult.message }));
        }
        break;
      }

      case "video_stream_stop": {
        if (!currentRoom) break;
        const vsStop = twitchStreamers.get(currentRoom);
        if (vsStop) {
          vsStop.stop();
          twitchStreamers.delete(currentRoom);
          ws.send(JSON.stringify({ type: "video_stream_stopped" }));
          console.log(`[VideoStream] Stopped for room ${currentRoom}`);
        }
        break;
      }

      case "twitch_chat_tts": {
        const text = msg.message;
        if (!text) break;
        for (const [code, room] of rooms) {
          if (room.ttsEnabled && room.creator && room.creator.readyState === 1) {
            room.creator.send(JSON.stringify({
              type: "glasses_message",
              message: text,
              timestamp: Date.now(),
            }));
          }
        }
        break;
      }

      case "tts_toggle": {
        if (!currentRoom || role !== "creator") break;
        const room = rooms.get(currentRoom);
        if (!room) break;
        room.ttsEnabled = !!msg.enabled;
        ws.send(JSON.stringify({ type: "tts_state", enabled: room.ttsEnabled }));
        console.log(`[TTS] Room ${currentRoom}: ${room.ttsEnabled ? 'enabled' : 'disabled'}`);
        if (room.viewer && room.viewer.readyState === 1) {
          room.viewer.send(JSON.stringify({ type: "tts_state", enabled: room.ttsEnabled }));
        }
        break;
      }

      // Relay SDP and ICE candidates to the other peer
      case "offer":
      case "answer":
      case "candidate": {
        const room = rooms.get(currentRoom);
        if (!room) {
          console.log(`[Relay] ${msg.type} from ${role} but room ${currentRoom} not found`);
          return;
        }
        const target = role === "creator" ? room.viewer : room.creator;
        if (target && target.readyState === 1) {
          target.send(JSON.stringify(msg));
          console.log(`[Relay] ${msg.type} from ${role} -> ${role === "creator" ? "viewer" : "creator"} (room ${currentRoom})`);
        } else {
          console.log(`[Relay] ${msg.type} from ${role} but target not ready (room ${currentRoom})`);
        }
        break;
      }
    }
  });

  ws.on("error", (err) => {
    console.log(`[WS] Error for ${role} in room ${currentRoom}: ${err.message}`);
  });

  ws.on("close", (code, reason) => {
    console.log(`[WS] Closed: ${role} in room ${currentRoom} (code=${code}, reason=${reason || "none"})`);

    if (currentRoom && rooms.has(currentRoom)) {
      const room = rooms.get(currentRoom);
      const otherPeer = role === "creator" ? room.viewer : room.creator;
      if (otherPeer && otherPeer.readyState === 1) {
        otherPeer.send(JSON.stringify({ type: "peer_left" }));
      }
      if (role === "creator") {
        room.creator = null;
        room.destroyTimer = setTimeout(() => {
          if (rooms.has(currentRoom)) {
            const r = rooms.get(currentRoom);
            if (!r.creator || r.creator.readyState !== 1) {
              if (r.viewer && r.viewer.readyState === 1) {
                r.viewer.send(JSON.stringify({ type: "error", message: "Stream ended" }));
              }
              const restreamer = twitchStreamers.get(currentRoom);
              if (restreamer) {
                restreamer.stop();
                twitchStreamers.delete(currentRoom);
              }
              rooms.delete(currentRoom);
              console.log(`[Room] Destroyed after grace period: ${currentRoom}`);
            }
          }
        }, ROOM_GRACE_PERIOD_MS);
        console.log(`[Room] Creator disconnected, grace period started (${ROOM_GRACE_PERIOD_MS / 1000}s): ${currentRoom}`);
        console.log(`[Twitch] Keeping Twitch stream alive — waiting for new device to connect`);
      } else {
        room.viewer = null;
        console.log(`[Room] Viewer disconnected from room ${currentRoom}`);
        const restreamer = twitchStreamers.get(currentRoom);
        if (restreamer && restreamer.isStreaming) {
          console.log(`[Twitch] Keeping stream alive after viewer disconnect — idle timeout will stop it if no new data`);
        }
      }
    }
  });
});

// Clean up stale OpenClaw sessions every 30 minutes
setInterval(() => {
  const now = Date.now();
  for (const [key, session] of sessions) {
    if (now - session.lastAccess.getTime() > 30 * 60 * 1000) {
      sessions.delete(key);
      console.log(`[OpenClaw] Cleaned up stale session: ${key}`);
    }
  }
}, 30 * 60 * 1000);

async function checkReminders() {
  try {
    const dueReminders = await memory.getDueReminders();
    for (const reminder of dueReminders) {
      const text = `Reminder: ${reminder.message}`;
      console.log(`[Reminder] Delivering: "${reminder.message}" to user ${reminder.user_id}`);

      let delivered = false;

      for (const [code, room] of rooms) {
        if (room.creator && room.creator.readyState === 1) {
          room.creator.send(JSON.stringify({
            type: "reminder",
            message: reminder.message,
            id: reminder.id,
          }));
          delivered = true;
          console.log(`[Reminder] Sent via WebSocket to room ${code}`);
        }
      }

      if (!delivered) {
        try {
          const bot = getBotInstance();
          const TELEGRAM_CHAT_ID = process.env.TELEGRAM_REMINDER_CHAT_ID;
          if (bot && TELEGRAM_CHAT_ID) {
            await sendTelegramMessage(parseInt(TELEGRAM_CHAT_ID), text);
            delivered = true;
            console.log(`[Reminder] Sent via Telegram to ${TELEGRAM_CHAT_ID}`);
          }
        } catch (err) {
          console.error(`[Reminder] Telegram delivery error:`, err.message);
        }
      }

      broadcastOpenClawMessage("system", text, "reminders");

      if (delivered) {
        await memory.markReminderDelivered(reminder.id);
      } else {
        console.log(`[Reminder] Could not deliver "${reminder.message}" — no channel available, will retry`);
      }
    }
  } catch (err) {
    console.error(`[Reminder] Check error:`, err.message);
  }
}

httpServer.listen(PORT, "0.0.0.0", () => {
  console.log(`Signaling server running on http://0.0.0.0:${PORT}`);
  console.log(`Web viewer available at http://localhost:${PORT}`);
  if (OPENCLAW_GATEWAY_TOKEN) {
    aiProviders.getSettings().then(s => {
      console.log(`[OpenClaw v2026.3.13] Gateway enabled at /v1/chat/completions (${s.provider}/${s.model})`);
    });
  } else {
    console.log(`[OpenClaw] Gateway disabled (no OPENCLAW_GATEWAY_TOKEN set)`);
  }
  setTools(OPENCLAW_TOOLS, executeToolCall);
  startTelegramBot(broadcastOpenClawMessage);

  setSendToGlasses((message) => {
    let sent = false;
    for (const [code, room] of rooms) {
      if (room.creator && room.creator.readyState === 1) {
        room.creator.send(JSON.stringify({
          type: "glasses_message",
          message: message,
          timestamp: Date.now(),
        }));
        sent = true;
        console.log(`[Glasses] Message sent to room ${code}: "${message.substring(0, 50)}"`);
      }
    }
    return sent;
  });

  setInterval(checkReminders, 60 * 1000);
  console.log(`[Reminder] Checker started (every 60s)`);
});
