const express = require('express');
const axios = require('axios');
const { AI_SERVICE_URL } = require('../middlewares/aiProxy');

const router = express.Router();

/**
 * 将 Python AI 服务上的 /uploads/* 代理到本后端，供手机通过 BASE_URL:3000 访问。
 * 例：GET http://192.168.x.x:3000/api/media/ai/uploads/output_xxx.jpg
 *   → http://localhost:8000/uploads/output_xxx.jpg
 */
router.get(/.*/, async (req, res) => {
  const subPath = req.path.startsWith('/') ? req.path : `/${req.path}`;
  if (!subPath.startsWith('/uploads/')) {
    return res.status(400).json({ error: '无效的图片路径' });
  }

  const target = `${AI_SERVICE_URL}${subPath}`;
  try {
    const resp = await axios.get(target, {
      responseType: 'stream',
      timeout: 60000,
      validateStatus: (s) => s === 200,
    });
    res.set('Content-Type', resp.headers['content-type'] || 'image/jpeg');
    res.set('Cache-Control', 'public, max-age=3600');
    resp.data.pipe(res);
  } catch (err) {
    console.error('[MediaProxy]', target, err.message);
    res.status(err.response?.status === 404 ? 404 : 502).json({
      error: '无法获取 AI 输出图片，请确认 AI 服务已启动',
    });
  }
});

module.exports = router;
