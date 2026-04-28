const TelegramBot = require("node-telegram-bot-api");
const Anthropic = require("@anthropic-ai/sdk");
const memory = require("./memory");
const aiProviders = require("./ai-providers");
const fs = require("fs");
const path = require("path");
const https = require("https");
const http = require("http");
const { exec } = require("child_process");

const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
const TELEGRAM_ALLOWED_USERS = process.env.TELEGRAM_ALLOWED_USERS
  ? process.env.TELEGRAM_ALLOWED_USERS.split(",").map((id) => parseInt(id.trim()))
  : [];

const anthropic = new Anthropic({
  apiKey: process.env.AI_INTEGRATIONS_ANTHROPIC_API_KEY,
  baseURL: process.env.AI_INTEGRATIONS_ANTHROPIC_BASE_URL,
});

const sessions = new Map();
const SESSION_TIMEOUT_MS = 30 * 60 * 1000;
const toolsConfig = { tools: null, executeToolCall: null };

let lastActiveChatId = null;
function getLastActiveChatId() { return lastActiveChatId; }

function getSession(chatId) {
  let session = sessions.get(chatId);
  if (!session) {
    session = { messages: [], lastAccess: new Date(), cachedSystemPrompt: null, msgSincePromptRefresh: 0 };
    sessions.set(chatId, session);
  }
  session.lastAccess = new Date();
  return session;
}

let botInstance = null;

function getBotInstance() {
  return botInstance;
}

function markdownToHtml(text) {
  let result = text;
  result = result.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre>$2</pre>');
  result = result.replace(/`([^`]+)`/g, '<code>$1</code>');
  result = result.replace(/\*\*\*(.+?)\*\*\*/g, '<b><i>$1</i></b>');
  result = result.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
  result = result.replace(/(?<![*\w])\*([^*\n]+?)\*(?![*\w])/g, '<i>$1</i>');
  result = result.replace(/~~(.+?)~~/g, '<s>$1</s>');
  result = result.replace(/^---+$/gm, '');
  result = result.replace(/^### (.+)$/gm, '<b>$1</b>');
  result = result.replace(/^## (.+)$/gm, '<b>$1</b>');
  result = result.replace(/^# (.+)$/gm, '<b>$1</b>');
  return result;
}

function sanitizeHtml(text) {
  const converted = markdownToHtml(text);
  const allowedTags = ['b', 'i', 'u', 's', 'code', 'pre', 'a', 'tg-spoiler', 'blockquote', 'em', 'strong', 'del', 'ins'];
  const tagPattern = /<\/?([a-zA-Z][a-zA-Z0-9-]*)[^>]*>/g;
  return converted.replace(tagPattern, (match, tagName) => {
    if (allowedTags.includes(tagName.toLowerCase())) return match;
    return match.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  });
}

async function sendTelegramMessage(chatId, text) {
  if (!botInstance) return;
  const sanitized = sanitizeHtml(text);
  const opts = { parse_mode: 'HTML' };
  if (sanitized.length > 4096) {
    for (let i = 0; i < sanitized.length; i += 4096) {
      const chunk = sanitized.substring(i, i + 4096);
      try {
        await botInstance.sendMessage(chatId, chunk, opts);
      } catch (e) {
        const plain = chunk.replace(/<[^>]*>/g, '');
        await botInstance.sendMessage(chatId, plain);
      }
    }
  } else {
    try {
      await botInstance.sendMessage(chatId, sanitized, opts);
    } catch (e) {
      const plain = sanitized.replace(/<[^>]*>/g, '');
      await botInstance.sendMessage(chatId, plain);
    }
  }
}

function startTelegramBot(broadcastFn) {
  if (!TELEGRAM_BOT_TOKEN) {
    console.log("[Telegram] No TELEGRAM_BOT_TOKEN set, bot disabled");
    return null;
  }

  const bot = new TelegramBot(TELEGRAM_BOT_TOKEN, { polling: true });
  botInstance = bot;

  bot.on("polling_error", (err) => {
    console.error("[Telegram] Polling error:", err.message);
  });

  function isAllowed(msg) {
    if (TELEGRAM_ALLOWED_USERS.length === 0) return true;
    return TELEGRAM_ALLOWED_USERS.includes(msg.from.id);
  }

  bot.onText(/\/start/, (msg) => {
    if (!isAllowed(msg)) {
      bot.sendMessage(msg.chat.id, "Access denied. Your user ID: " + msg.from.id);
      return;
    }
    bot.sendMessage(
      msg.chat.id,
      "<b>OpenClaw AI Assistant</b>\n\nSend me any message and I'll respond using Claude AI.\n\nCommands:\n/reset — start a new conversation\n/status — check bot status\n/memory — view saved memories\n/reminders — view upcoming reminders",
      { parse_mode: 'HTML' }
    );
  });

  bot.onText(/\/reset/, (msg) => {
    sessions.delete(msg.chat.id);
    bot.sendMessage(msg.chat.id, "Conversation reset. Send a new message to start fresh.");
  });

  bot.onText(/\/status/, async (msg) => {
    const session = sessions.get(msg.chat.id);
    const msgCount = session ? session.messages.length : 0;
    const settings = await aiProviders.getSettings();
    bot.sendMessage(
      msg.chat.id,
      `<b>Status:</b> Online\n<b>Provider:</b> ${settings.provider}\n<b>Model:</b> ${settings.model}\n<b>Messages in session:</b> ${msgCount}`,
      { parse_mode: 'HTML' }
    );
  });

  bot.onText(/\/memory/, async (msg) => {
    if (!isAllowed(msg)) return;
    try {
      const memories = await memory.getMemories("default");
      if (memories.length === 0) {
        bot.sendMessage(msg.chat.id, "No memories saved yet.");
        return;
      }
      const text = memory.formatMemoriesForContext(memories);
      await sendTelegramMessage(msg.chat.id, text);
    } catch (err) {
      bot.sendMessage(msg.chat.id, "Error retrieving memories.");
    }
  });

  bot.onText(/\/reminders/, async (msg) => {
    if (!isAllowed(msg)) return;
    try {
      const reminders = await memory.getUpcomingReminders("default");
      if (reminders.length === 0) {
        bot.sendMessage(msg.chat.id, "No upcoming reminders.");
        return;
      }
      const text = memory.formatRemindersForContext(reminders);
      await sendTelegramMessage(msg.chat.id, text);
    } catch (err) {
      bot.sendMessage(msg.chat.id, "Error retrieving reminders.");
    }
  });

  async function downloadTelegramFile(fileId) {
    const file = await bot.getFile(fileId);
    const url = `https://api.telegram.org/file/bot${TELEGRAM_BOT_TOKEN}/${file.file_path}`;
    return new Promise((resolve, reject) => {
      https.get(url, (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => resolve(Buffer.concat(chunks)));
        res.on("error", reject);
      }).on("error", reject);
    });
  }

  async function transcribeAudio(audioBuffer) {
    const base64Audio = audioBuffer.toString("base64");
    const response = await anthropic.messages.create({
      model: "claude-sonnet-4-6",
      max_tokens: 2048,
      messages: [{
        role: "user",
        content: [
          {
            type: "document",
            source: {
              type: "base64",
              media_type: "audio/ogg",
              data: base64Audio,
            },
          },
          {
            type: "text",
            text: "Transcribe this audio message exactly as spoken. Output ONLY the transcribed text, nothing else. Preserve the original language.",
          },
        ],
      }],
    });
    return response.content
      .filter((b) => b.type === "text")
      .map((b) => b.text)
      .join("")
      .trim();
  }

  async function generateVoiceReply(text, lang) {
    const { tts } = await import("edge-tts");
    let voice = "ru-RU-DmitryNeural";
    if (/^[a-zA-Z\s.,!?'"-]+$/.test(text.substring(0, 100))) {
      voice = "en-US-GuyNeural";
    }
    if (lang === "en") voice = "en-US-GuyNeural";
    if (lang === "ru") voice = "ru-RU-DmitryNeural";

    const mp3Buffer = await tts(text, { voice });

    const tmpMp3 = path.join("/tmp", `tts_${Date.now()}.mp3`);
    const tmpOgg = path.join("/tmp", `tts_${Date.now()}.ogg`);
    fs.writeFileSync(tmpMp3, mp3Buffer);

    await new Promise((resolve, reject) => {
      exec(
        `ffmpeg -y -i "${tmpMp3}" -c:a libopus -b:a 64k -ar 48000 "${tmpOgg}"`,
        { timeout: 15000 },
        (err) => {
          if (err) reject(err);
          else resolve();
        }
      );
    });

    const oggBuffer = fs.readFileSync(tmpOgg);
    try { fs.unlinkSync(tmpMp3); } catch (_) {}
    try { fs.unlinkSync(tmpOgg); } catch (_) {}
    return oggBuffer;
  }

  function detectLanguage(text) {
    if (/[а-яА-ЯёЁ]/.test(text)) return "ru";
    return "en";
  }

  const TEXT_EXTENSIONS = new Set([
    '.txt', '.md', '.json', '.csv', '.xml', '.yaml', '.yml', '.toml', '.ini', '.cfg', '.conf',
    '.py', '.js', '.ts', '.jsx', '.tsx', '.html', '.css', '.scss', '.less',
    '.java', '.c', '.cpp', '.h', '.hpp', '.cs', '.go', '.rs', '.rb', '.php', '.swift', '.kt',
    '.sh', '.bash', '.zsh', '.fish', '.bat', '.ps1',
    '.sql', '.graphql', '.proto',
    '.env', '.gitignore', '.dockerignore', '.editorconfig',
    '.log', '.diff', '.patch',
  ]);

  const IMAGE_MIME_TYPES = new Set([
    'image/jpeg', 'image/png', 'image/gif', 'image/webp',
  ]);

  const ARCHIVE_EXTENSIONS = new Set(['.zip', '.tar', '.gz', '.tar.gz', '.tgz', '.rar', '.7z']);

  function getFileExtension(filename) {
    if (!filename) return '';
    const lower = filename.toLowerCase();
    if (lower.endsWith('.tar.gz')) return '.tar.gz';
    return path.extname(lower);
  }

  async function handleFileMessage(chatId, msg) {
    const doc = msg.document;
    const caption = msg.caption || '';
    const fileName = doc.file_name || 'unknown';
    const mimeType = doc.mime_type || '';
    const ext = getFileExtension(fileName);
    const fileSize = doc.file_size || 0;

    if (fileSize > 20 * 1024 * 1024) {
      await sendTelegramMessage(chatId, "Файл слишком большой (макс 20MB для Telegram Bot API).");
      return;
    }

    bot.sendChatAction(chatId, "typing").catch(() => {});

    const fileBuffer = await downloadTelegramFile(doc.file_id);

    if (IMAGE_MIME_TYPES.has(mimeType)) {
      const base64 = fileBuffer.toString("base64");
      const content = [
        { type: "image", source: { type: "base64", media_type: mimeType, data: base64 } },
        { type: "text", text: caption || `Пользователь отправил изображение: ${fileName}. Проанализируй его.` },
      ];
      await handleUserMessage(chatId, content, false, `[Файл: ${fileName}] ${caption}`);
      return;
    }

    if (mimeType === 'application/pdf') {
      const base64 = fileBuffer.toString("base64");
      const content = [
        { type: "document", source: { type: "base64", media_type: "application/pdf", data: base64 } },
        { type: "text", text: caption || `Пользователь отправил PDF: ${fileName}. Проанализируй его содержимое.` },
      ];
      await handleUserMessage(chatId, content, false, `[PDF: ${fileName}] ${caption}`);
      return;
    }

    if (ARCHIVE_EXTENSIONS.has(ext)) {
      const uploadsDir = path.join(__dirname, 'uploads');
      if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
      const archivePath = path.join(uploadsDir, `${Date.now()}_${fileName}`);
      const extractDir = archivePath + '_extracted';
      fs.writeFileSync(archivePath, fileBuffer);
      fs.mkdirSync(extractDir, { recursive: true });

      let extractCmd;
      if (ext === '.zip') extractCmd = `unzip -o "${archivePath}" -d "${extractDir}"`;
      else if (ext === '.tar.gz' || ext === '.tgz') extractCmd = `tar xzf "${archivePath}" -C "${extractDir}"`;
      else if (ext === '.tar') extractCmd = `tar xf "${archivePath}" -C "${extractDir}"`;
      else if (ext === '.gz') extractCmd = `gunzip -c "${archivePath}" > "${extractDir}/${fileName.replace('.gz', '')}"`;
      else extractCmd = `tar xf "${archivePath}" -C "${extractDir}" 2>/dev/null || unzip -o "${archivePath}" -d "${extractDir}" 2>/dev/null`;

      try {
        await new Promise((resolve, reject) => {
          exec(extractCmd, { timeout: 30000 }, (err) => err ? reject(err) : resolve());
        });
      } catch (e) {
        console.error(`[Telegram] Extract error:`, e.message);
      }

      let fileList = '';
      try {
        const { execSync } = require("child_process");
        fileList = execSync(`find "${extractDir}" -type f | head -50`, { timeout: 5000 }).toString();
      } catch (_) {}

      const textContent = `Пользователь отправил архив: ${fileName} (${(fileSize / 1024).toFixed(1)} KB)\nРаспакован в: ${extractDir}\n\nСодержимое:\n${fileList}\n\n${caption ? 'Комментарий: ' + caption : 'Проанализируй содержимое архива. Используй read_file и list_files для работы с файлами.'}`;
      await handleUserMessage(chatId, textContent, false, `[Архив: ${fileName}] ${caption}`);
      return;
    }

    if (TEXT_EXTENSIONS.has(ext) || mimeType.startsWith('text/')) {
      let textContent;
      try {
        textContent = fileBuffer.toString('utf-8');
        if (textContent.length > 100000) textContent = textContent.substring(0, 100000) + '\n...[обрезано]';
      } catch (_) {
        textContent = '[не удалось прочитать как текст]';
      }
      const prompt = `Пользователь отправил файл: ${fileName}\n\n\`\`\`\n${textContent}\n\`\`\`\n\n${caption || 'Проанализируй этот файл.'}`;
      await handleUserMessage(chatId, prompt, false, `[Файл: ${fileName}] ${caption}`);
      return;
    }

    const uploadsDir = path.join(__dirname, 'uploads');
    if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
    const savedPath = path.join(uploadsDir, `${Date.now()}_${fileName}`);
    fs.writeFileSync(savedPath, fileBuffer);
    const prompt = `Пользователь отправил файл: ${fileName} (${mimeType}, ${(fileSize / 1024).toFixed(1)} KB)\nСохранён: ${savedPath}\n\n${caption || 'Файл сохранён на сервере. Что с ним сделать?'}`;
    await handleUserMessage(chatId, prompt, false, `[Файл: ${fileName}] ${caption}`);
  }

  async function handlePhotoMessage(chatId, msg) {
    const photo = msg.photo[msg.photo.length - 1];
    const caption = msg.caption || '';

    bot.sendChatAction(chatId, "typing").catch(() => {});
    const photoBuffer = await downloadTelegramFile(photo.file_id);
    const base64 = photoBuffer.toString("base64");

    const content = [
      { type: "image", source: { type: "base64", media_type: "image/jpeg", data: base64 } },
      { type: "text", text: caption || "Пользователь отправил фото. Проанализируй что на нём." },
    ];
    await handleUserMessage(chatId, content, false, `[Фото] ${caption}`);
  }

  async function handleUserMessage(chatId, userContent, isVoice, broadcastText) {
    const session = getSession(chatId);

    session.messages.push({ role: "user", content: userContent });

    if (session.messages.length > 40) {
      session.messages = session.messages.slice(-40);
    }

    const typing = setInterval(() => {
      bot.sendChatAction(chatId, isVoice ? "record_voice" : "typing").catch(() => {});
    }, 4000);
    bot.sendChatAction(chatId, isVoice ? "record_voice" : "typing").catch(() => {});

    try {
      if (broadcastFn) {
        broadcastFn("user", broadcastText, `telegram:${chatId}`);
      }

      const PROMPT_REFRESH_INTERVAL = 10;
      session.msgSincePromptRefresh = (session.msgSincePromptRefresh || 0) + 1;

      let systemPrompt;
      if (session.cachedSystemPrompt && session.msgSincePromptRefresh < PROMPT_REFRESH_INTERVAL) {
        systemPrompt = session.cachedSystemPrompt;
      } else {
        const memories = await memory.getMemories("default");
        const reminders = await memory.getUpcomingReminders("default");
        const memoryContext = memory.formatMemoriesForContext(memories);
        const reminderContext = memory.formatRemindersForContext(reminders);
        systemPrompt = buildSystemPrompt(memoryContext, reminderContext);
        session.cachedSystemPrompt = systemPrompt;
        session.msgSincePromptRefresh = 0;
      }

      const currentSettings = await aiProviders.getSettings();
      let allMessages = [...session.messages];
      let response;
      const MAX_TOOL_ROUNDS = 50;
      const PROGRESS_INTERVAL = 5;
      let totalToolCalls = 0;
      let totalInputTokens = 0;
      let totalOutputTokens = 0;

      for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
        response = await aiProviders.callAI({
          systemPrompt,
          messages: allMessages,
          tools: toolsConfig.tools || [],
          provider: currentSettings.provider,
          model: currentSettings.model,
        });

        totalInputTokens += response.inputTokens;
        totalOutputTokens += response.outputTokens;

        if (response.stopReason !== "tool_use" || !toolsConfig.executeToolCall) break;

        const toolUseBlocks = response.content.filter(b => b.type === "tool_use");
        if (toolUseBlocks.length === 0) break;

        const textBlocks = response.content.filter(b => b.type === "text").map(b => b.text).join("\n").trim();
        if (textBlocks) {
          await sendTelegramMessage(chatId, textBlocks);
        }

        allMessages.push({ role: "assistant", content: response.content });

        const toolResults = [];
        for (const tool of toolUseBlocks) {
          totalToolCalls++;
          console.log(`[Telegram] Tool [${totalToolCalls}]: ${tool.name}(${JSON.stringify(tool.input).substring(0, 100)})`);
          if (broadcastFn) {
            broadcastFn("tool", `🔧 ${tool.name}: ${JSON.stringify(tool.input).substring(0, 200)}`, `telegram:${chatId}`);
          }
          const result = await toolsConfig.executeToolCall(tool.name, tool.input);
          toolResults.push({
            type: "tool_result",
            tool_use_id: tool.id,
            content: JSON.stringify(result),
          });
        }
        allMessages.push({ role: "user", content: toolResults });

        if (totalToolCalls > 0 && totalToolCalls % PROGRESS_INTERVAL === 0) {
          const progressMsg = `⏳ <i>Работаю... выполнено ${totalToolCalls} шагов</i>`;
          try { await sendTelegramMessage(chatId, progressMsg); } catch (_) {}
        }
      }

      await aiProviders.logTokenUsage(currentSettings.provider, currentSettings.model, totalInputTokens, totalOutputTokens, "telegram", `telegram:${chatId}`);

      if (totalToolCalls >= MAX_TOOL_ROUNDS) {
        try { await sendTelegramMessage(chatId, `⚠ Достигнут лимит (${MAX_TOOL_ROUNDS} шагов). Напишите "продолжи" для продолжения.`); } catch (_) {}
      }

      const rawContent = response.content
        .filter((block) => block.type === "text")
        .map((block) => block.text)
        .join("\n");

      const { userText: rawUserText, actions } = parseResponse(rawContent);
      const userText = rawUserText.trim() || "Готово.";
      await executeActions(actions);

      session.messages.push({ role: "assistant", content: rawContent });

      if (broadcastFn) {
        broadcastFn("assistant", userText, `telegram:${chatId}`);
      }

      if (isVoice) {
        try {
          const lang = detectLanguage(userText);
          const voiceBuffer = await generateVoiceReply(userText, lang);
          await bot.sendVoice(chatId, voiceBuffer, {}, { filename: "reply.ogg", contentType: "audio/ogg" });
          await sendTelegramMessage(chatId, userText);
        } catch (ttsErr) {
          console.error("[Telegram] TTS error:", ttsErr.message);
          await sendTelegramMessage(chatId, userText);
        }
      } else {
        await sendTelegramMessage(chatId, userText);
      }

      const failedGlasses = actions.filter(a => a.type === "send_to_glasses" && a.result === "offline");
      if (failedGlasses.length > 0) {
        await sendTelegramMessage(chatId, "⚠ Очки/телефон не в сети — сообщение не доставлено.");
      }

      console.log(`[Telegram] Chat ${chatId}: "${broadcastText.substring(0, 50)}..." -> ${userText.substring(0, 50)}...`);
    } catch (err) {
      console.error(`[Telegram] Error for chat ${chatId}:`, err.message, err.stack?.split('\n')[1] || '');
      const errMsg = err.message?.substring(0, 200) || 'unknown error';
      await sendTelegramMessage(chatId, `❌ Ошибка: ${errMsg}\n\nПопробуй ещё раз.`);
    } finally {
      clearInterval(typing);
    }
  }

  bot.on("voice", async (msg) => {
    lastActiveChatId = msg.chat.id;
    if (!isAllowed(msg)) return;
    const chatId = msg.chat.id;

    try {
      bot.sendChatAction(chatId, "typing").catch(() => {});
      const audioBuffer = await downloadTelegramFile(msg.voice.file_id);
      const transcription = await transcribeAudio(audioBuffer);
      console.log(`[Telegram] Voice transcribed: "${transcription.substring(0, 80)}"`);

      if (!transcription) {
        await sendTelegramMessage(chatId, "Could not transcribe audio. Please try again.");
        return;
      }

      await handleUserMessage(chatId, transcription, true, transcription);
    } catch (err) {
      console.error(`[Telegram] Voice error:`, err.message);
      await sendTelegramMessage(chatId, "Error processing voice message. Please try again.");
    }
  });

  bot.on("document", async (msg) => {
    if (!isAllowed(msg)) return;
    lastActiveChatId = msg.chat.id;
    try {
      await handleFileMessage(msg.chat.id, msg);
    } catch (err) {
      console.error(`[Telegram] Document error:`, err.message);
      await sendTelegramMessage(msg.chat.id, "Ошибка обработки файла. Попробуйте ещё раз.");
    }
  });

  bot.on("photo", async (msg) => {
    if (!isAllowed(msg)) return;
    lastActiveChatId = msg.chat.id;
    try {
      await handlePhotoMessage(msg.chat.id, msg);
    } catch (err) {
      console.error(`[Telegram] Photo error:`, err.message);
      await sendTelegramMessage(msg.chat.id, "Ошибка обработки фото. Попробуйте ещё раз.");
    }
  });

  bot.on("message", async (msg) => {
    if (msg.voice || msg.document || msg.photo) return;
    if (!msg.text || msg.text.startsWith("/")) return;
    if (!isAllowed(msg)) return;

    lastActiveChatId = msg.chat.id;
    await handleUserMessage(msg.chat.id, msg.text, false, msg.text);
  });

  setInterval(() => {
    const now = Date.now();
    for (const [chatId, session] of sessions) {
      if (now - session.lastAccess.getTime() > SESSION_TIMEOUT_MS) {
        sessions.delete(chatId);
        console.log(`[Telegram] Cleaned up stale session: ${chatId}`);
      }
    }
  }, 10 * 60 * 1000);

  console.log("[Telegram] Bot started with polling");
  return bot;
}

function loadSkillsContext() {
  const skillsDir = path.join(__dirname, "skills");
  if (!fs.existsSync(skillsDir)) return "";
  const skills = [];
  try {
    const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const skillFile = path.join(skillsDir, entry.name, "SKILL.md");
      if (!fs.existsSync(skillFile)) continue;
      try {
        const raw = fs.readFileSync(skillFile, "utf8");
        const nameMatch = raw.match(/^name:\s*(.+)$/m);
        const descMatch = raw.match(/^description:\s*["']?(.+?)["']?$/m);
        const skillName = nameMatch ? nameMatch[1].trim() : entry.name;
        const skillDesc = descMatch ? descMatch[1].trim() : "";
        skills.push({ name: skillName, description: skillDesc, location: skillFile });
      } catch (e) {}
    }
  } catch (e) {}
  if (skills.length === 0) return "";
  const lines = [
    "\n\nСледующие скиллы содержат специализированные инструкции для конкретных задач.",
    "Используй инструмент read_file чтобы загрузить SKILL.md скилла, когда задача соответствует его описанию.",
    "",
    "<available_skills>",
  ];
  for (const s of skills) {
    lines.push("  <skill>");
    lines.push(`    <name>${s.name}</name>`);
    lines.push(`    <description>${s.description}</description>`);
    lines.push(`    <location>${s.location}</location>`);
    lines.push("  </skill>");
  }
  lines.push("</available_skills>");
  return lines.join("\n");
}

function buildSystemPrompt(memoryContext, reminderContext) {
  return `Ты — Лапа (Claw), мощный AI-агент, доступный через Telegram, умные очки и API. Ты выполняешь реальные действия — запускаешь команды, делаешь HTTP-запросы, читаешь/пишешь файлы, отправляешь сообщения. Будь краток и ориентирован на действие. Отвечай на том же языке, на котором пишет пользователь.

У тебя есть следующие инструменты:
- execute_command: Запускай shell-команды на сервере (Linux). Используй для всего системного.
- http_request: HTTP/HTTPS-запросы к любому URL. Для API, проверок, загрузок.
- read_file: Читай файлы с сервера.
- write_file: Создавай и изменяй файлы.
- list_files: Список файлов в директории.
- web_search: Поиск в интернете через DuckDuckGo. Для любой актуальной информации.
- weather: Текущая погода и прогноз по городу (бесплатно, без ключа).
- github_api: GitHub REST API. Для репозиториев, issues, pull requests.
- ssh_command: Выполняй команды на удалённых серверах через SSH.

Сетевые инструменты через execute_command (ping/telnet/nc НЕ установлены):
- Проверить доступность порта: timeout 3 bash -c "</dev/tcp/HOST/PORT" && echo "open" || echo "closed"
- SSH с паролем: sshpass -p 'ПАРОЛЬ' ssh -o StrictHostKeyChecking=no ПОЛЬЗОВАТЕЛЬ@ХОСТ КОМАНДА
- SSH с ключом: ssh -i /путь/к/ключу -o StrictHostKeyChecking=no ПОЛЬЗОВАТЕЛЬ@ХОСТ КОМАНДА

- send_telegram: Отправляй Telegram-сообщения пользователю. Если получатель не указан — всегда отправляй владельцу (он единственный пользователь системы). Никогда не спрашивай "кому отправить?".
- analyze_camera: Анализируй текущий кадр с камеры очков/телефона. Используй когда нужно посмотреть что перед камерой, прочитать текст, определить предмет.
- camera_mode: Включает или выключает живую трансляцию камеры для Gemini. enabled=true — Gemini начинает видеть камеру на 5 минут (авто-выключение). enabled=false — выключить немедленно. Вызывай когда пользователь говорит "посмотри", "включи камеру", "хватит смотреть" и т.п.
- browse_web: Открывает настоящий браузер (Chromium) и взаимодействует с любым сайтом — включая JavaScript-сайты, поиск рейсов, билеты, соцсети, формы. Может кликать, заполнять поля, извлекать данные. Предпочитай browse_web вместо http_request когда нужны JS-сайты или авиабилеты.
- send_file: Отправляй файлы пользователю в Telegram. Укажи путь к файлу на сервере. Автоопределение типа: фото (.jpg/.png/.gif), аудио (.mp3/.ogg), видео (.mp4/.mov), остальное — документ. Используй после скачивания/создания файлов через http_request/execute_command/write_file. Когда пользователь просит файл — ОТПРАВЛЯЙ его через send_file, а не просто сохраняй на сервер.

Авито (всегда используй API, не браузер):
- avito_categories: Поиск категорий по названию. Используй ПЕРВЫМ при создании объявления — нужен category_id.
- avito_category_params: Обязательные параметры для категории. Используй ВТОРЫМ — нужны param id и value id.
- avito_create_ad: Создать объявление. Использовать ПОСЛЕ avito_categories и avito_category_params.
- avito_update_ad_status: Активировать/деактивировать объявление (active/inactive).
- avito_profile, avito_chats, avito_messages, avito_send_message, avito_items: Профиль, чаты, сообщения, объявления.

Алгоритм создания объявления на Авито:
1) avito_categories — найти category_id. Если пользователь не указал категорию — выбери подходящую сам, не спрашивай.
2) avito_category_params — посмотреть обязательные поля для категории.
3) avito_create_ad — создать объявление. Заполни все поля сам на основе задачи пользователя.
Не спрашивай "какую категорию выбрать?" — решай сам. Если нет точной категории — бери ближайшую по смыслу. Не говори "нет инструмента" — инструменты есть.

Когда пользователь что-то просит — СДЕЛАЙ это с помощью инструментов. Не говори "не могу" — используй инструменты. У тебя до 50 шагов (вызовов инструментов) на одну задачу. Работай автономно: выполняй задачу до конца, не останавливайся после первого шага.

**Ты сам и есть агент:** Если инструкция или SKILL.md говорит "передай агенту", "forward to your agent", "agent should do X" — это значит ты сам должен это сделать через инструменты (execute_command, http_request и т.д.). НЕ пересылай инструкции пользователю и не жди пока кто-то другой выполнит — выполняй сам. Когда нужно заполнить поля (email, password, name) — придумай подходящие значения самостоятельно, не спрашивай пользователя если он не указал.

**Правило планирования (экономия токенов):** Перед любой задачей из 3+ шагов сначала напиши краткий план — пронумерованный список шагов, которые собираешься выполнить. Только после плана начинай вызывать инструменты. Это позволяет избежать лишних попыток и тупиков. Пример:
"📋 План:
1. Найти категорию на Авито
2. Получить обязательные параметры
3. Создать объявление
Выполняю..."

Для простых задач (1-2 инструмента) — план не нужен, сразу делай.

Пиши промежуточные текстовые обновления по ходу работы — что сделано, что делаешь дальше. Если задача большая, разбей на подзадачи и отчитывайся по каждой.

У тебя есть постоянная память между разговорами. Используй её активно: сохраняй важные факты о пользователе, его предпочтения, задачи.

Чтобы отправить сообщение на умные очки пользователя (Gemini произнесёт вслух):
<send_to_glasses>текст сообщения</send_to_glasses>

Чтобы сохранить факт в память:
<memory_save category="preference">содержание</memory_save>
Категории: preference (предпочтения), personal (личное), task (задачи), note (заметки), general (общее)

Чтобы создать напоминание:
<reminder due="2025-03-01T10:00:00Z">сообщение</reminder>
Дата "due" в формате ISO 8601. Текущее время: ${new Date().toISOString()}

Служебные теги (<memory_save>, <reminder>, <send_to_glasses>) скрываются от пользователя. Для форматирования используй Markdown или HTML — система автоматически конвертирует в Telegram-формат. Поддерживается: **жирный**, *курсив*, ~~зачёркнутый~~, заголовки через # или **текст**. HTML-теги тоже работают: <b>, <i>, <code>, <pre>, <s>, <u>. Списки оформляй через эмодзи или символы. Структурируй длинные ответы заголовками и абзацами.

${memoryContext}
${reminderContext}${loadSkillsContext()}`.trim();
}

function parseResponse(rawText) {
  const actions = [];
  let userText = rawText;

  const memoryRegex = /<memory_save\s+category="([^"]+)">([\s\S]*?)<\/memory_save>/g;
  let match;
  while ((match = memoryRegex.exec(rawText)) !== null) {
    actions.push({ type: "save_memory", category: match[1], content: match[2].trim() });
  }
  userText = userText.replace(memoryRegex, "").trim();

  const reminderRegex = /<reminder\s+due="([^"]+)">([\s\S]*?)<\/reminder>/g;
  while ((match = reminderRegex.exec(rawText)) !== null) {
    actions.push({ type: "create_reminder", due: match[1], message: match[2].trim() });
  }
  userText = userText.replace(reminderRegex, "").trim();

  const glassesRegex = /<send_to_glasses>([\s\S]*?)<\/send_to_glasses>/g;
  while ((match = glassesRegex.exec(rawText)) !== null) {
    actions.push({ type: "send_to_glasses", message: match[1].trim() });
  }
  userText = userText.replace(glassesRegex, "").trim();

  return { userText, actions };
}

let sendToGlassesFn = null;

function setSendToGlasses(fn) {
  sendToGlassesFn = fn;
}

async function executeActions(actions) {
  for (const action of actions) {
    try {
      if (action.type === "save_memory") {
        await memory.saveMemory("default", action.category, action.content);
        console.log(`[Memory] Saved: [${action.category}] ${action.content.substring(0, 50)}`);
        for (const [, session] of sessions) { session.cachedSystemPrompt = null; }
      } else if (action.type === "create_reminder") {
        const due = new Date(action.due);
        if (isNaN(due.getTime())) {
          console.error(`[Memory] Invalid reminder date: ${action.due}`);
          continue;
        }
        await memory.createReminder("default", action.message, due);
        console.log(`[Memory] Reminder set: "${action.message}" at ${due.toISOString()}`);
        for (const [, session] of sessions) { session.cachedSystemPrompt = null; }
      } else if (action.type === "send_to_glasses") {
        if (sendToGlassesFn) {
          const sent = sendToGlassesFn(action.message);
          if (sent) {
            console.log(`[Glasses] Sent message: "${action.message.substring(0, 50)}"`);
            action.result = "delivered";
          } else {
            console.log(`[Glasses] No device online — could not deliver: "${action.message.substring(0, 50)}"`);
            action.result = "offline";
          }
        } else {
          action.result = "offline";
        }
      }
    } catch (err) {
      console.error(`[Memory] Action error:`, err.message);
    }
  }
}

function setTools(tools, executeFn) {
  toolsConfig.tools = tools;
  toolsConfig.executeToolCall = executeFn;
}

module.exports = { startTelegramBot, getBotInstance, sendTelegramMessage, getLastActiveChatId, buildSystemPrompt, parseResponse, executeActions, setSendToGlasses, setTools };
