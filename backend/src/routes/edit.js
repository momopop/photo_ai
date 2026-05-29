const express = require('express');
const router = express.Router();
const upload = require('../middlewares/upload');
const { proxyToAI, buildClientMediaUrl } = require('../middlewares/aiProxy');
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
      // 统一走本后端代理，手机只访问 BASE_URL:3000，不直连 localhost:8000
      result.display_url = buildClientMediaUrl(req, result.output_url);
      result.output_full_url = result.display_url;
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
