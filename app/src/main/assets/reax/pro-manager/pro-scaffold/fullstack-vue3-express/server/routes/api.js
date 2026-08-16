const express = require('express');
const router = express.Router();
const db = require('../db');

// 健康检查
router.get('/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// 获取所有项目
router.get('/items', async (req, res) => {
  try {
    const items = await db.getAll('items');
    res.json({ success: true, data: items });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 获取单个项目
router.get('/items/:id', async (req, res) => {
  try {
    const item = await db.getById('items', parseInt(req.params.id));
    if (!item) return res.status(404).json({ error: 'Not found' });
    res.json({ success: true, data: item });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 创建项目
router.post('/items', async (req, res) => {
  try {
    const { name, description } = req.body;
    if (!name) return res.status(400).json({ error: 'Name is required' });
    const item = await db.create('items', { name, description: description || '' });
    res.json({ success: true, data: item });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 更新项目
router.put('/items/:id', async (req, res) => {
  try {
    const { name, description } = req.body;
    const item = await db.update('items', parseInt(req.params.id), { name, description });
    if (!item) return res.status(404).json({ error: 'Not found' });
    res.json({ success: true, data: item });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 删除项目
router.delete('/items/:id', async (req, res) => {
  try {
    const success = await db.remove('items', parseInt(req.params.id));
    if (!success) return res.status(404).json({ error: 'Not found' });
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;

