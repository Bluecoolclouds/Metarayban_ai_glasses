const { Pool } = require("pg");

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: false,
});

const VALID_CATEGORIES = ["preference", "personal", "task", "note", "general"];
const MAX_MEMORY_CONTENT_LENGTH = 500;

async function saveMemory(userId, category, content) {
  if (!VALID_CATEGORIES.includes(category)) category = "general";
  content = content.substring(0, MAX_MEMORY_CONTENT_LENGTH);

  const existing = await pool.query(
    "SELECT id FROM user_memories WHERE user_id = $1 AND category = $2 AND content = $3",
    [userId, category, content]
  );
  if (existing.rows.length > 0) {
    await pool.query(
      "UPDATE user_memories SET updated_at = NOW() WHERE id = $1",
      [existing.rows[0].id]
    );
    return existing.rows[0].id;
  }
  const result = await pool.query(
    "INSERT INTO user_memories (user_id, category, content) VALUES ($1, $2, $3) RETURNING id",
    [userId, category, content]
  );
  return result.rows[0].id;
}

async function getMemories(userId, category = null, limit = 20) {
  if (category) {
    const result = await pool.query(
      "SELECT id, category, content, created_at FROM user_memories WHERE user_id = $1 AND category = $2 ORDER BY updated_at DESC LIMIT $3",
      [userId, category, limit]
    );
    return result.rows;
  }
  const result = await pool.query(
    "SELECT id, category, content, created_at FROM user_memories WHERE user_id = $1 ORDER BY updated_at DESC LIMIT $2",
    [userId, limit]
  );
  return result.rows;
}

async function deleteMemory(userId, memoryId) {
  await pool.query(
    "DELETE FROM user_memories WHERE id = $1 AND user_id = $2",
    [memoryId, userId]
  );
}

async function searchMemories(userId, query) {
  const result = await pool.query(
    "SELECT id, category, content, created_at FROM user_memories WHERE user_id = $1 AND content ILIKE $2 ORDER BY updated_at DESC LIMIT 20",
    [userId, `%${query}%`]
  );
  return result.rows;
}

async function createReminder(userId, message, dueAt) {
  const result = await pool.query(
    "INSERT INTO reminders (user_id, message, due_at) VALUES ($1, $2, $3) RETURNING id, due_at",
    [userId, message, dueAt]
  );
  return result.rows[0];
}

async function getDueReminders() {
  const result = await pool.query(
    "SELECT id, user_id, message, due_at FROM reminders WHERE due_at <= NOW() AND NOT delivered ORDER BY due_at ASC"
  );
  return result.rows;
}

async function markReminderDelivered(reminderId) {
  await pool.query(
    "UPDATE reminders SET delivered = TRUE WHERE id = $1",
    [reminderId]
  );
}

async function getUpcomingReminders(userId) {
  const result = await pool.query(
    "SELECT id, message, due_at FROM reminders WHERE user_id = $1 AND NOT delivered ORDER BY due_at ASC LIMIT 20",
    [userId]
  );
  return result.rows;
}

async function deleteReminder(userId, reminderId) {
  await pool.query(
    "DELETE FROM reminders WHERE id = $1 AND user_id = $2",
    [reminderId, userId]
  );
}

function formatMemoriesForContext(memories) {
  if (!memories || memories.length === 0) return "";
  const grouped = {};
  for (const m of memories) {
    if (!grouped[m.category]) grouped[m.category] = [];
    grouped[m.category].push(m.content);
  }
  let text = "=== USER MEMORY ===\n";
  for (const [cat, items] of Object.entries(grouped)) {
    text += `[${cat}]\n`;
    for (const item of items) {
      text += `- ${item}\n`;
    }
  }
  return text;
}

function formatRemindersForContext(reminders) {
  if (!reminders || reminders.length === 0) return "";
  let text = "=== UPCOMING REMINDERS ===\n";
  for (const r of reminders) {
    const due = new Date(r.due_at).toLocaleString();
    text += `- [ID:${r.id}] "${r.message}" — due: ${due}\n`;
  }
  return text;
}

module.exports = {
  pool,
  saveMemory,
  getMemories,
  deleteMemory,
  searchMemories,
  createReminder,
  getDueReminders,
  markReminderDelivered,
  getUpcomingReminders,
  deleteReminder,
  formatMemoriesForContext,
  formatRemindersForContext,
};
