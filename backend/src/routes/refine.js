const express = require('express');
const router = express.Router();
const upload = require('../middlewares/upload');
const { proxyToAI, buildClientMediaUrl } = require('../middlewares/aiProxy');
const fs = require('fs');

/**
 * POST /api/refine
 * AI 精修：超分辨率 + HDR 增强 + 轻量锐化（独立于 /api/edit 的过度锐化版本）
 * 参数：
 *   image        : 图片文件（multipart/form-data）
 *   target_mp    : 目标百万像素（默认 8.0，即约 3000x2667 的竖图）
 */
router.post('/', upload.single('image'), async (req, res, next) => {
  if (!req.file) {
    return res.status(400).json({ error: '请上传图片' });
  }

  const targetMp = parseFloat(req.body.target_mp || '8.0');

  try {
    const result = await proxyToAI('/api/refine', req.file.path, {
      target_megapixels: String(isNaN(targetMp) ? 8.0 : targetMp),
    });

    if (result.output_url) {
      result.display_url    = buildClientMediaUrl(req, result.output_url);
      result.output_full_url = result.display_url;
    }

    res.json({ success: true, data: result });
  } catch (err) {
    console.error('[Refine] 错误:', err.message);
    next(err);
  } finally {
    setTimeout(() => {
      if (req.file && fs.existsSync(req.file.path)) {
        fs.unlinkSync(req.file.path);
      }
    }, 60000);
  }
});

module.exports = router;
