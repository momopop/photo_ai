const express = require('express');
const router = express.Router();
const upload = require('../middlewares/upload');
const { proxyToAI, AI_SERVICE_URL } = require('../middlewares/aiProxy');
const fs = require('fs');

/**
 * POST /api/edit
 * 修图接口，支持亮度/对比度/饱和度/锐化/降噪/裁剪
 */
router.post('/', upload.single('image'), async (req, res, next) => {
  if (!req.file) {
    return res.status(400).json({ error: '请上传图片' });
  }

  const {
    brightness = '0',
    contrast = '0',
    saturation = '0',
    sharpness = '0',
    denoise = 'false',
    auto_enhance = 'false',
    crop_x = '0',
    crop_y = '0',
    crop_w = '0',
    crop_h = '0',
  } = req.body;

  try {
    const result = await proxyToAI('/api/edit', req.file.path, {
      brightness,
      contrast,
      saturation,
      sharpness,
      denoise,
      auto_enhance,
      crop_x,
      crop_y,
      crop_w,
      crop_h,
    });

    if (result.output_url) {
      const filename = result.output_url.split('/').pop();
      result.output_full_url = `${req.protocol}://${req.get('host')}${result.output_url}`;
      // 代理 AI 服务图片
      result.proxy_url = `${AI_SERVICE_URL}${result.output_url}`;
    }

    res.json({
      success: true,
      data: result,
    });
  } catch (err) {
    console.error('[Edit] 错误:', err.message);
    next(err);
  } finally {
    setTimeout(() => {
      if (req.file && fs.existsSync(req.file.path)) {
        fs.unlinkSync(req.file.path);
      }
    }, 30000);
  }
});

module.exports = router;
