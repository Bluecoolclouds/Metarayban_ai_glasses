const https = require("https");
const querystring = require("querystring");

const BASE = "api.avito.ru";

let _token = null;
let _tokenExpiry = 0;
let _userId = null;

async function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const bodyStr = body ? JSON.stringify(body) : undefined;
    const headers = {
      "Accept": "application/json",
      ...(token ? { "Authorization": `Bearer ${token}` } : {}),
      ...(bodyStr ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(bodyStr) } : {}),
    };

    const req = https.request({ hostname: BASE, path, method, headers }, (res) => {
      let data = "";
      res.on("data", (c) => { data += c; });
      res.on("end", () => {
        try {
          resolve({ status: res.statusCode, body: JSON.parse(data) });
        } catch {
          resolve({ status: res.statusCode, body: data });
        }
      });
    });
    req.on("error", reject);
    if (bodyStr) req.write(bodyStr);
    req.end();
  });
}

async function formRequest(method, path, formData) {
  return new Promise((resolve, reject) => {
    const bodyStr = querystring.stringify(formData);
    const headers = {
      "Content-Type": "application/x-www-form-urlencoded",
      "Content-Length": Buffer.byteLength(bodyStr),
    };
    const req = https.request({ hostname: BASE, path, method, headers }, (res) => {
      let data = "";
      res.on("data", (c) => { data += c; });
      res.on("end", () => {
        try {
          resolve({ status: res.statusCode, body: JSON.parse(data) });
        } catch {
          resolve({ status: res.statusCode, body: data });
        }
      });
    });
    req.on("error", reject);
    req.write(bodyStr);
    req.end();
  });
}

async function getToken() {
  if (_token && Date.now() < _tokenExpiry) return _token;

  const clientId = process.env.AVITO_CLIENT_ID;
  const clientSecret = process.env.AVITO_CLIENT_SECRET;
  if (!clientId || !clientSecret) throw new Error("AVITO_CLIENT_ID / AVITO_CLIENT_SECRET не заданы");

  const res = await formRequest("POST", "/token", {
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
  });

  if (res.status !== 200 || !res.body.access_token) {
    throw new Error(`Авито OAuth ошибка: ${res.status} ${JSON.stringify(res.body)}`);
  }

  _token = res.body.access_token;
  _tokenExpiry = Date.now() + (res.body.expires_in || 86400) * 1000 - 60000;
  _userId = null;
  return _token;
}

async function getUserId() {
  if (_userId) return _userId;
  const token = await getToken();
  const res = await request("GET", "/core/v1/accounts/self", null, token);
  if (res.status !== 200) throw new Error(`Не удалось получить профиль: ${res.status} ${JSON.stringify(res.body)}`);
  _userId = res.body.id;
  return _userId;
}

async function getChats({ limit = 20, offset = 0, unread_only = false } = {}) {
  const token = await getToken();
  const userId = await getUserId();
  const fetchLimit = unread_only ? Math.min(limit * 5, 100) : limit;
  const qs = querystring.stringify({ limit: fetchLimit, offset });
  const res = await request("GET", `/messenger/v2/accounts/${userId}/chats?${qs}`, null, token);
  if (unread_only && res.status === 200 && Array.isArray(res.body?.chats)) {
    const filtered = res.body.chats.filter(c => (c.counters?.unread_messages_count || 0) > 0).slice(0, limit);
    return { status: 200, body: { ...res.body, chats: filtered } };
  }
  return res;
}

async function getMessages(chatId, { limit = 20, offset_id } = {}) {
  const token = await getToken();
  const userId = await getUserId();
  const qs = querystring.stringify({ limit, ...(offset_id ? { offset_id } : {}) });
  const res = await request("GET", `/messenger/v2/accounts/${userId}/chats/${chatId}/messages/?${qs}`, null, token);
  return res;
}

async function sendMessage(chatId, text) {
  const token = await getToken();
  const userId = await getUserId();
  const res = await request(
    "POST",
    `/messenger/v2/accounts/${userId}/chats/${chatId}/messages`,
    { message: { text }, type: "text" },
    token
  );
  return res;
}

async function getItems({ limit = 25, offset = 0, status } = {}) {
  const token = await getToken();
  const userId = await getUserId();
  const qs = querystring.stringify({ limit, offset, ...(status ? { status } : {}) });
  const res = await request("GET", `/core/v1/accounts/${userId}/items?${qs}`, null, token);
  return res;
}

async function getProfile() {
  const token = await getToken();
  const res = await request("GET", "/core/v1/accounts/self", null, token);
  return res;
}

async function getCategories(query) {
  const token = await getToken();
  const res = await request("GET", "/core/v1/categories", null, token);
  if (res.status !== 200) return res;

  const allCategories = res.body;
  if (!query) return res;

  const q = query.toLowerCase();
  function flattenAndSearch(cats, depth) {
    if (!Array.isArray(cats)) return [];
    let results = [];
    for (const cat of cats) {
      if ((cat.name || "").toLowerCase().includes(q)) {
        results.push({ id: cat.id, name: cat.name, depth });
      }
      if (cat.children) {
        results = results.concat(flattenAndSearch(cat.children, depth + 1));
      }
    }
    return results;
  }

  const found = flattenAndSearch(Array.isArray(allCategories) ? allCategories : [allCategories], 0);

  if (found.length === 0) {
    return {
      status: 200,
      body: {
        message: `Категория "${query}" не найдена. Верхние категории:`,
        top_categories: (Array.isArray(allCategories) ? allCategories : []).slice(0, 20).map(c => ({ id: c.id, name: c.name })),
      },
    };
  }

  return { status: 200, body: { found, query } };
}

async function getCategoryParams(categoryId) {
  const token = await getToken();
  const res = await request("GET", `/core/v1/categories/${categoryId}/params`, null, token);
  return res;
}

async function createAd({ category_id, location_id = 621540, title, description, price, params, contact_phone }) {
  const token = await getToken();
  const userId = await getUserId();
  const body = {
    category: { id: category_id },
    location: { id: location_id },
    title,
    description,
    price,
    ...(params && params.length > 0 ? { params } : {}),
    ...(contact_phone ? { contacts: { phone: contact_phone } } : {}),
  };
  const res = await request("POST", `/core/v1/accounts/${userId}/items`, body, token);
  return res;
}

async function updateAdStatus(itemId, status) {
  const token = await getToken();
  const userId = await getUserId();
  const res = await request("PUT", `/core/v1/accounts/${userId}/items/${itemId}/status/${status}`, null, token);
  return res;
}

module.exports = { getChats, getMessages, sendMessage, getItems, getProfile, getCategories, getCategoryParams, createAd, updateAdStatus };
