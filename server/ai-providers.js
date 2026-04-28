const Anthropic = require("@anthropic-ai/sdk");
const OpenAI = require("openai");
const { GoogleGenAI } = require("@google/genai");
const { Pool } = require("pg");

const pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: false });

let _anthropic = null;
let _openai = null;
let _gemini = null;
let _deepseek = null;

function getAnthropic() {
  if (!_anthropic) {
    _anthropic = new Anthropic({
      apiKey: process.env.AI_INTEGRATIONS_ANTHROPIC_API_KEY || "placeholder",
      baseURL: process.env.AI_INTEGRATIONS_ANTHROPIC_BASE_URL,
    });
  }
  return _anthropic;
}

function getOpenAI() {
  if (!_openai) {
    _openai = new OpenAI({
      apiKey: process.env.AI_INTEGRATIONS_OPENAI_API_KEY || "placeholder",
      baseURL: process.env.AI_INTEGRATIONS_OPENAI_BASE_URL,
    });
  }
  return _openai;
}

function getDeepSeek() {
  if (!_deepseek) {
    _deepseek = new OpenAI({
      apiKey: process.env.DEEPSEEK_API_KEY || "placeholder",
      baseURL: "https://api.deepseek.com",
    });
  }
  return _deepseek;
}

function getGemini() {
  if (!_gemini) {
    const integrationKey = process.env.AI_INTEGRATIONS_GEMINI_API_KEY;
    const integrationUrl = process.env.AI_INTEGRATIONS_GEMINI_BASE_URL;
    const directKey = process.env.GEMINI_API_KEY;
    if (integrationKey && integrationUrl) {
      _gemini = new GoogleGenAI({
        apiKey: integrationKey,
        httpOptions: { apiVersion: "", baseUrl: integrationUrl },
      });
    } else if (directKey) {
      _gemini = new GoogleGenAI({ apiKey: directKey });
    } else {
      _gemini = new GoogleGenAI({ apiKey: "placeholder" });
    }
  }
  return _gemini;
}

const PROVIDERS = {
  anthropic: {
    name: "Anthropic",
    models: [
      { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", tier: "balanced", inputPrice: 3.00, outputPrice: 15.00 },
      { id: "claude-haiku-4-5", name: "Claude Haiku 4.5", tier: "fast", inputPrice: 1.00, outputPrice: 5.00 },
      { id: "claude-opus-4-6", name: "Claude Opus 4.6", tier: "powerful", inputPrice: 5.00, outputPrice: 25.00 },
    ],
  },
  openai: {
    name: "OpenAI",
    models: [
      { id: "gpt-5.2", name: "GPT-5.2", tier: "powerful", inputPrice: 1.25, outputPrice: 10.00 },
      { id: "gpt-5.1", name: "GPT-5.1", tier: "balanced", inputPrice: 1.25, outputPrice: 10.00 },
      { id: "gpt-5-mini", name: "GPT-5 Mini", tier: "fast", inputPrice: 0.40, outputPrice: 1.60 },
      { id: "gpt-5-nano", name: "GPT-5 Nano", tier: "fastest", inputPrice: 0.05, outputPrice: 0.40 },
      { id: "gpt-4o", name: "GPT-4o", tier: "legacy", inputPrice: 2.50, outputPrice: 10.00 },
      { id: "gpt-4o-mini", name: "GPT-4o Mini", tier: "legacy", inputPrice: 0.15, outputPrice: 0.60 },
      { id: "o4-mini", name: "o4-mini (Reasoning)", tier: "reasoning", inputPrice: 1.10, outputPrice: 4.40 },
      { id: "o3", name: "o3 (Reasoning)", tier: "reasoning", inputPrice: 10.00, outputPrice: 40.00 },
    ],
  },
  gemini: {
    name: "Google Gemini",
    models: [
      { id: "gemini-2.5-flash", name: "Gemini 2.5 Flash", tier: "fast", inputPrice: 0.30, outputPrice: 2.50 },
      { id: "gemini-2.5-pro", name: "Gemini 2.5 Pro", tier: "powerful", inputPrice: 1.25, outputPrice: 10.00 },
      { id: "gemini-3-flash-preview", name: "Gemini 3 Flash Preview", tier: "fast", inputPrice: 0.50, outputPrice: 3.00 },
      { id: "gemini-3-pro-preview", name: "Gemini 3 Pro Preview", tier: "balanced", inputPrice: 2.00, outputPrice: 12.00 },
      { id: "gemini-3.1-pro-preview", name: "Gemini 3.1 Pro Preview", tier: "powerful", inputPrice: 2.00, outputPrice: 12.00 },
    ],
  },
  deepseek: {
    name: "DeepSeek",
    models: [
      { id: "deepseek-chat", name: "DeepSeek V3", tier: "balanced", inputPrice: 0.27, outputPrice: 1.10 },
      { id: "deepseek-reasoner", name: "DeepSeek R1 (Reasoning)", tier: "reasoning", inputPrice: 0.55, outputPrice: 2.19 },
    ],
  },
};

function calculateCost(provider, model, inputTokens, outputTokens) {
  const providerInfo = PROVIDERS[provider];
  if (!providerInfo) return 0;
  const modelInfo = providerInfo.models.find(m => m.id === model);
  if (!modelInfo) return 0;
  const inputCost = (inputTokens / 1_000_000) * modelInfo.inputPrice;
  const outputCost = (outputTokens / 1_000_000) * modelInfo.outputPrice;
  return inputCost + outputCost;
}

async function getSettings() {
  const result = await pool.query("SELECT provider, model FROM ai_settings ORDER BY id DESC LIMIT 1");
  if (result.rows.length === 0) return { provider: "gemini", model: "gemini-2.5-flash" };
  return result.rows[0];
}

async function updateSettings(provider, model) {
  const validProvider = PROVIDERS[provider];
  if (!validProvider) throw new Error(`Unknown provider: ${provider}`);
  const validModel = validProvider.models.find(m => m.id === model);
  if (!validModel) throw new Error(`Unknown model: ${model} for provider ${provider}`);
  const result = await pool.query("UPDATE ai_settings SET provider = $1, model = $2, updated_at = NOW()", [provider, model]);
  if (result.rowCount === 0) {
    await pool.query("INSERT INTO ai_settings (provider, model) VALUES ($1, $2)", [provider, model]);
  }
  return { provider, model };
}

async function logTokenUsage(provider, model, inputTokens, outputTokens, source, sessionKey) {
  await pool.query(
    "INSERT INTO token_usage (provider, model, input_tokens, output_tokens, source, session_key) VALUES ($1, $2, $3, $4, $5, $6)",
    [provider, model, inputTokens, outputTokens, source, sessionKey || null]
  );
}

async function getTokenStats() {
  const total = await pool.query(`
    SELECT 
      provider, model,
      SUM(input_tokens) as total_input,
      SUM(output_tokens) as total_output,
      SUM(input_tokens + output_tokens) as total_tokens,
      COUNT(*) as request_count
    FROM token_usage
    GROUP BY provider, model
    ORDER BY total_tokens DESC
  `);

  const today = await pool.query(`
    SELECT 
      provider, model,
      SUM(input_tokens) as total_input,
      SUM(output_tokens) as total_output,
      SUM(input_tokens + output_tokens) as total_tokens,
      COUNT(*) as request_count
    FROM token_usage
    WHERE created_at >= CURRENT_DATE
    GROUP BY provider, model
    ORDER BY total_tokens DESC
  `);

  const grandTotal = await pool.query(`
    SELECT 
      COALESCE(SUM(input_tokens), 0) as total_input,
      COALESCE(SUM(output_tokens), 0) as total_output,
      COALESCE(SUM(input_tokens + output_tokens), 0) as total_tokens,
      COUNT(*) as request_count
    FROM token_usage
  `);

  const todayTotal = await pool.query(`
    SELECT 
      COALESCE(SUM(input_tokens), 0) as total_input,
      COALESCE(SUM(output_tokens), 0) as total_output,
      COALESCE(SUM(input_tokens + output_tokens), 0) as total_tokens,
      COUNT(*) as request_count
    FROM token_usage
    WHERE created_at >= CURRENT_DATE
  `);

  const addCosts = (rows) => rows.map(row => ({
    ...row,
    cost: calculateCost(row.provider, row.model, parseInt(row.total_input) || 0, parseInt(row.total_output) || 0),
  }));

  const byModelWithCost = addCosts(total.rows);
  const todayByModelWithCost = addCosts(today.rows);
  const totalCost = byModelWithCost.reduce((sum, r) => sum + r.cost, 0);
  const todayCost = todayByModelWithCost.reduce((sum, r) => sum + r.cost, 0);

  return {
    byModel: byModelWithCost,
    todayByModel: todayByModelWithCost,
    allTime: { ...grandTotal.rows[0], cost: totalCost },
    today: { ...todayTotal.rows[0], cost: todayCost },
  };
}

async function callAI({ systemPrompt, messages, tools, provider, model }) {
  const settings = provider && model ? { provider, model } : await getSettings();

  const doCall = () => {
    switch (settings.provider) {
      case "anthropic":
        return callAnthropic(settings.model, systemPrompt, messages, tools);
      case "openai":
        return callOpenAI(settings.model, systemPrompt, messages, tools);
      case "gemini":
        return callGemini(settings.model, systemPrompt, messages, tools);
      case "deepseek":
        return callDeepSeek(settings.model, systemPrompt, messages, tools);
      default:
        throw new Error(`Unknown provider: ${settings.provider}`);
    }
  };

  try {
    return await doCall();
  } catch (err) {
    const msg = err.message || "";
    const retryMatch = msg.match(/retry[^\d]*(\d+(?:\.\d+)?)\s*s/i);
    if ((msg.includes("429") || msg.includes("RESOURCE_EXHAUSTED") || msg.includes("quota")) && retryMatch) {
      const delaySec = Math.min(parseFloat(retryMatch[1]) + 2, 60);
      console.warn(`[AI] Rate limit hit, retrying in ${delaySec}s...`);
      await new Promise(r => setTimeout(r, delaySec * 1000));
      return await doCall();
    }
    throw err;
  }
}

async function callAnthropic(model, systemPrompt, messages, tools) {
  const response = await getAnthropic().messages.create({
    model,
    max_tokens: 8192,
    system: systemPrompt,
    messages,
    ...(tools && tools.length > 0 ? { tools } : {}),
  });

  const inputTokens = response.usage?.input_tokens || 0;
  const outputTokens = response.usage?.output_tokens || 0;

  return {
    provider: "anthropic",
    model,
    content: response.content,
    stopReason: response.stop_reason,
    inputTokens,
    outputTokens,
    rawResponse: response,
  };
}

async function callOpenAI(model, systemPrompt, messages, tools) {
  const openaiMessages = [];
  if (systemPrompt) {
    openaiMessages.push({ role: "system", content: systemPrompt });
  }
  for (const m of messages) {
    if (typeof m.content === "string") {
      openaiMessages.push({ role: m.role, content: m.content });
    } else if (Array.isArray(m.content)) {
      const textParts = m.content.filter(b => b.type === "text").map(b => b.text || b.content || "");
      const toolResults = m.content.filter(b => b.type === "tool_result");
      const toolUseBlocks = m.content.filter(b => b.type === "tool_use");

      if (toolUseBlocks.length > 0) {
        openaiMessages.push({
          role: "assistant",
          content: textParts.length > 0 ? textParts.join("\n") : null,
          tool_calls: toolUseBlocks.map(t => ({
            id: t.id,
            type: "function",
            function: { name: t.name, arguments: JSON.stringify(t.input) },
          })),
        });
      } else if (textParts.length > 0) {
        openaiMessages.push({ role: m.role, content: textParts.join("\n") });
      }

      if (toolResults.length > 0) {
        for (const tr of toolResults) {
          openaiMessages.push({
            role: "tool",
            tool_call_id: tr.tool_use_id,
            content: typeof tr.content === "string" ? tr.content : JSON.stringify(tr.content),
          });
        }
      }
    }
  }

  const openaiTools = tools && tools.length > 0 ? tools.map(t => ({
    type: "function",
    function: {
      name: t.name,
      description: t.description,
      parameters: t.input_schema,
    },
  })) : undefined;

  const response = await getOpenAI().chat.completions.create({
    model,
    messages: openaiMessages,
    max_completion_tokens: 8192,
    ...(openaiTools ? { tools: openaiTools } : {}),
  });

  const choice = response.choices[0];
  const inputTokens = response.usage?.prompt_tokens || 0;
  const outputTokens = response.usage?.completion_tokens || 0;

  const content = [];
  if (choice.message.content) {
    content.push({ type: "text", text: choice.message.content });
  }
  if (choice.message.tool_calls) {
    for (const tc of choice.message.tool_calls) {
      content.push({
        type: "tool_use",
        id: tc.id,
        name: tc.function.name,
        input: JSON.parse(tc.function.arguments),
      });
    }
  }

  let stopReason = "end_turn";
  if (choice.finish_reason === "tool_calls") stopReason = "tool_use";
  else if (choice.finish_reason === "length") stopReason = "max_tokens";

  return {
    provider: "openai",
    model,
    content,
    stopReason,
    inputTokens,
    outputTokens,
    rawResponse: response,
  };
}

async function callGemini(model, systemPrompt, messages, tools) {
  const toolIdToName = {};
  const geminiContents = [];
  for (const m of messages) {
    const role = m.role === "assistant" ? "model" : "user";
    if (typeof m.content === "string") {
      geminiContents.push({ role, parts: [{ text: m.content }] });
    } else if (Array.isArray(m.content)) {
      const functionCallParts = [];
      const functionResponseParts = [];
      const textParts = [];

      for (const block of m.content) {
        if (block.type === "text") {
          textParts.push({ text: block.text });
        } else if (block.type === "tool_use") {
          toolIdToName[block.id] = block.name;
          functionCallParts.push({
            functionCall: { name: block.name, args: block.input },
          });
        } else if (block.type === "tool_result") {
          const fnName = toolIdToName[block.tool_use_id] || block.tool_use_id;
          let responseData;
          try {
            responseData = typeof block.content === "string" ? JSON.parse(block.content) : block.content;
          } catch (_) {
            responseData = { result: block.content };
          }
          functionResponseParts.push({
            functionResponse: { name: fnName, response: responseData },
          });
        }
      }
      if (functionCallParts.length > 0 || textParts.length > 0) {
        geminiContents.push({ role: "model", parts: [...textParts, ...functionCallParts] });
      }
      if (functionResponseParts.length > 0) {
        geminiContents.push({ role: "user", parts: functionResponseParts });
      }
    }
  }

  const geminiTools = tools && tools.length > 0 ? [{
    functionDeclarations: tools.map(t => ({
      name: t.name,
      description: t.description,
      parameters: t.input_schema,
    })),
  }] : undefined;

  const config = {
    maxOutputTokens: 8192,
    ...(systemPrompt ? { systemInstruction: { role: "user", parts: [{ text: systemPrompt }] } } : {}),
    ...(geminiTools ? {
      tools: geminiTools,
      toolConfig: { functionCallingConfig: { mode: "AUTO" } },
    } : {}),
  };

  const response = await getGemini().models.generateContent({
    model,
    contents: geminiContents,
    config,
  });

  const inputTokens = response.usageMetadata?.promptTokenCount || 0;
  const outputTokens = response.usageMetadata?.candidatesTokenCount || 0;

  const content = [];
  const candidate = response.candidates?.[0];
  if (candidate?.content?.parts) {
    for (const part of candidate.content.parts) {
      if (part.text) {
        content.push({ type: "text", text: part.text });
      }
      if (part.functionCall) {
        content.push({
          type: "tool_use",
          id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
          name: part.functionCall.name,
          input: part.functionCall.args || {},
        });
      }
    }
  }

  const hasToolUse = content.some(c => c.type === "tool_use");
  const stopReason = hasToolUse ? "tool_use" : "end_turn";

  return {
    provider: "gemini",
    model,
    content,
    stopReason,
    inputTokens,
    outputTokens,
    rawResponse: response,
  };
}

async function callDeepSeek(model, systemPrompt, messages, tools) {
  const dsMessages = [];
  if (systemPrompt) dsMessages.push({ role: "system", content: systemPrompt });

  for (const m of messages) {
    if (typeof m.content === "string") {
      dsMessages.push({ role: m.role, content: m.content });
    } else if (Array.isArray(m.content)) {
      const textParts = m.content.filter(b => b.type === "text").map(b => b.text || "");
      const toolResults = m.content.filter(b => b.type === "tool_result");
      const toolUseBlocks = m.content.filter(b => b.type === "tool_use");

      if (toolUseBlocks.length > 0) {
        dsMessages.push({
          role: "assistant",
          content: textParts.length > 0 ? textParts.join("\n") : null,
          tool_calls: toolUseBlocks.map(t => ({
            id: t.id,
            type: "function",
            function: { name: t.name, arguments: JSON.stringify(t.input) },
          })),
        });
      } else if (textParts.length > 0) {
        dsMessages.push({ role: m.role, content: textParts.join("\n") });
      }

      for (const tr of toolResults) {
        dsMessages.push({
          role: "tool",
          tool_call_id: tr.tool_use_id,
          content: typeof tr.content === "string" ? tr.content : JSON.stringify(tr.content),
        });
      }
    }
  }

  const dsTools = tools && tools.length > 0 ? tools.map(t => ({
    type: "function",
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  })) : undefined;

  const response = await getDeepSeek().chat.completions.create({
    model,
    messages: dsMessages,
    max_tokens: 8192,
    ...(dsTools ? { tools: dsTools } : {}),
  });

  const choice = response.choices[0];
  const inputTokens = response.usage?.prompt_tokens || 0;
  const outputTokens = response.usage?.completion_tokens || 0;

  const content = [];
  if (choice.message.content) content.push({ type: "text", text: choice.message.content });
  if (choice.message.tool_calls) {
    for (const tc of choice.message.tool_calls) {
      content.push({
        type: "tool_use",
        id: tc.id,
        name: tc.function.name,
        input: JSON.parse(tc.function.arguments),
      });
    }
  }

  let stopReason2 = "end_turn";
  if (choice.finish_reason === "tool_calls") stopReason2 = "tool_use";
  else if (choice.finish_reason === "length") stopReason2 = "max_tokens";

  return { provider: "deepseek", model, content, stopReason: stopReason2, inputTokens, outputTokens, rawResponse: response };
}

module.exports = {
  PROVIDERS,
  calculateCost,
  getSettings,
  updateSettings,
  logTokenUsage,
  getTokenStats,
  callAI,
  get anthropic() { return getAnthropic(); },
  get openai() { return getOpenAI(); },
  get gemini() { return getGemini(); },
};
