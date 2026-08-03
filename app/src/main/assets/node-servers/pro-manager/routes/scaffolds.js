const express = require('express');
const router = express.Router();
const scaffoldService = require('../services/scaffoldService');

// GET /api/scaffolds - 获取手脚架列表
router.get('/', (req, res) => {
  try {
    const scaffolds = scaffoldService.getList();
    res.json({ success: true, data: scaffolds });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
