const path = require('path');
const initSqlJs = require('sql.js');

const DB_PATH = path.join(__dirname, 'data', 'db.sqlite');

let db;

async function initDB() {
  const fs = require('fs');
  const dir = path.dirname(DB_PATH);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const SQL = await initSqlJs();

  // 如果已有数据库文件，则加载；否则创建空数据库
  if (fs.existsSync(DB_PATH)) {
    const buffer = fs.readFileSync(DB_PATH);
    db = new SQL.Database(buffer);
  } else {
    db = new SQL.Database();
  }

  db.run('PRAGMA journal_mode = WAL');
  db.run('PRAGMA foreign_keys = ON');

  db.run(`
    CREATE TABLE IF NOT EXISTS items (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      description TEXT DEFAULT '',
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    )
  `);

  saveDB();
  return db;
}

function saveDB() {
  const fs = require('fs');
  const data = db.export();
  const buffer = Buffer.from(data);
  fs.writeFileSync(DB_PATH, buffer);
}

function safeTable(table) {
  if (table !== 'items') throw new Error('Invalid table name');
  return table;
}

async function getAll(table) {
  const t = safeTable(table);
  const stmt = db.prepare(`SELECT * FROM ${t} ORDER BY id ASC`);
  const result = [];
  while (stmt.step()) {
    result.push(stmt.getAsObject());
  }
  stmt.free();
  return result;
}

async function getById(table, id) {
  const t = safeTable(table);
  const stmt = db.prepare(`SELECT * FROM ${t} WHERE id = ?`);
  stmt.bind([id]);
  let result = null;
  if (stmt.step()) {
    result = stmt.getAsObject();
  }
  stmt.free();
  return result;
}

async function create(table, data) {
  const t = safeTable(table);
  const now = new Date().toISOString();
  db.run(
    `INSERT INTO ${t} (name, description, created_at, updated_at) VALUES (?, ?, ?, ?)`,
    [data.name, data.description || '', now, now]
  );
  saveDB();
  const id = db.exec(`SELECT last_insert_rowid() as id`)[0].values[0][0];
  return getById(table, id);
}

async function update(table, id, data) {
  const t = safeTable(table);
  const now = new Date().toISOString();
  const sets = [];
  const params = [];
  if (data.name !== undefined) {
    sets.push('name = ?');
    params.push(data.name);
  }
  if (data.description !== undefined) {
    sets.push('description = ?');
    params.push(data.description);
  }
  if (sets.length === 0) {
    return getById(table, id);
  }
  sets.push('updated_at = ?');
  params.push(now);
  params.push(id);

  db.run(
    `UPDATE ${t} SET ${sets.join(', ')} WHERE id = ?`,
    params
  );
  saveDB();
  return getById(table, id);
}

async function remove(table, id) {
  const t = safeTable(table);
  db.run(`DELETE FROM ${t} WHERE id = ?`, [id]);
  saveDB();
  const changes = db.getRowsModified();
  return changes > 0;
}

module.exports = {
  initDB,
  getAll,
  getById,
  create,
  update,
  remove
};



