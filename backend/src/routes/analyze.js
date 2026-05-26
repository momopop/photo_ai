const express = require('express');
const router = express.Router();
const upload = require('../middlewares/upload');
const { proxyToAI, AI_SERVICE_URL } = require('../middlewares/aiProxy');
const fs = require('fs');

/**
 * POST /api/analyze
 * 上传图片 → AI 检测 + 构图分析
 */
router.post('/', upload.single('image'), async (req, res, next) => {
  if (!req.file) {
    return res.status(400).json({ error: '请上传图片' });
  }

  try {
    const result = await proxyToAI('/api/analyze', req.file.path);

    // 将 AI 服务的 URL 替换为我们自己的代理 URL
    if (result.file_url) {
      result.file_url = result.file_url.replace(
        /^\/uploads\//,
        `/uploads/`
      );
      // 构建完整 URL（客户端可直接使用）
      result.image_url = `${req.protocol}://${req.get('host')}/uploads/${req.file.filename}`;
    }

    res.json({
      success: true,
      data: result,
    });
  } catch (err) {
    console.error('[Analyze] 错误:', err.message);
    err.status = err.status || 500;
    next(err);
  } finally {
    // 延迟清理临时文件
    setTimeout(() => {
      if (req.file && fs.existsSync(req.file.path)) {
        fs.unlinkSync(req.file.path);
      }
    }, 30000);
  }
});

module.exports = router;
