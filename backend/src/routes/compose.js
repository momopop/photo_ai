const express = require('express');
const router = express.Router();
const upload = require('../middlewares/upload');
const { proxyToAI, AI_SERVICE_URL, checkAIService } = require('../middlewares/aiProxy');
const fs = require('fs');

/**
 * POST /api/compose/auto
 * 全自动构图：检测 → 分析 → 修图建议 → 预览图
 */
router.post('/auto', upload.single('image'), async (req, res, next) => {
  if (!req.file) {
    return res.status(400).json({ error: '请上传图片' });
  }

  try {
    const result = await proxyToAI('/api/auto-compose', req.file.path);

    // 构建代理 URL
    if (result.preview_url) {
      result.preview_full_url = `${AI_SERVICE_URL}${result.preview_url}`;
    }
    if (result.original_url) {
      result.original_full_url = `${AI_SERVICE_URL}${result.original_url}`;
    }

    res.json({
      success: true,
      data: result,
    });
  } catch (err) {
    console.error('[AutoCompose] 错误:', err.message);
    next(err);
  } finally {
    setTimeout(() => {
      if (req.file && fs.existsSync(req.file.path)) {
        fs.unlinkSync(req.file.path);
      }
    }, 60000);
  }
});

/**
 * GET /api/compose/health
 * 检查 AI 服务状态
 */
router.get('/health', async (req, res) => {
  const alive = await checkAIService();
  res.json({
    backend: 'ok',
    ai_service: alive ? 'ok' : 'unavailable',
    ai_service_url: AI_SERVICE_URL,
  });
});

module.exports = router;
