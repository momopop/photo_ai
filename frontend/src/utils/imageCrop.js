/**
 * 使用 Canvas 对本地图片进行旋转、翻转、裁切
 */

function normalizeSrc(src) {
  if (!src) return src;
  if (src.startsWith('http') || src.startsWith('file://') || src.startsWith('blob:')) {
    return src;
  }
  // #ifdef APP-PLUS
  if (src.startsWith('/')) return 'file://' + src;
  // #endif
  return src;
}

export function getImageInfo(src) {
  return new Promise((resolve, reject) => {
    uni.getImageInfo({
      src: normalizeSrc(src),
      success: resolve,
      fail: reject,
    });
  });
}

function canvasExport(canvasId, w, h, instance) {
  return new Promise((resolve, reject) => {
    uni.canvasToTempFilePath(
      {
        canvasId,
        x: 0,
        y: 0,
        width: w,
        height: h,
        destWidth: w,
        destHeight: h,
        fileType: 'jpg',
        quality: 0.95,
        success: (res) => resolve(res.tempFilePath),
        fail: reject,
      },
      instance
    );
  });
}

function waitDraw(ctx, delay = 320) {
  return new Promise((resolve) => {
    ctx.draw(false, () => setTimeout(resolve, delay));
  });
}

/** 旋转后包围盒尺寸 */
function rotatedBounds(w, h, deg) {
  const rad = (deg * Math.PI) / 180;
  const cos = Math.abs(Math.cos(rad));
  const sin = Math.abs(Math.sin(rad));
  return {
    width: Math.ceil(w * cos + h * sin),
    height: Math.ceil(w * sin + h * cos),
  };
}

/**
 * 以图片中心旋转任意角度（度）
 */
export async function rotateImageByDegrees(src, degrees, componentInstance) {
  const info = await getImageInfo(src);
  const w = info.width;
  const h = info.height;
  const deg = ((degrees % 360) + 360) % 360;
  if (deg === 0) return src;

  const bounds = rotatedBounds(w, h, deg);
  const canvasW = bounds.width;
  const canvasH = bounds.height;
  const ctx = uni.createCanvasContext('imgProcCanvas', componentInstance);

  ctx.clearRect(0, 0, canvasW, canvasH);
  ctx.translate(canvasW / 2, canvasH / 2);
  ctx.rotate((deg * Math.PI) / 180);
  ctx.drawImage(info.path, -w / 2, -h / 2, w, h);
  await waitDraw(ctx);

  return canvasExport('imgProcCanvas', canvasW, canvasH, componentInstance);
}

export async function rotateImage90(src, componentInstance) {
  return rotateImageByDegrees(src, 90, componentInstance);
}

/**
 * 水平 / 垂直翻转
 */
export async function flipImage(src, flipH, flipV, componentInstance) {
  if (!flipH && !flipV) return src;
  const info = await getImageInfo(src);
  const w = info.width;
  const h = info.height;
  const ctx = uni.createCanvasContext('imgProcCanvas', componentInstance);
  ctx.clearRect(0, 0, w, h);
  ctx.translate(flipH ? w : 0, flipV ? h : 0);
  ctx.scale(flipH ? -1 : 1, flipV ? -1 : 1);
  ctx.drawImage(info.path, 0, 0, w, h);
  await waitDraw(ctx);
  return canvasExport('imgProcCanvas', w, h, componentInstance);
}

export async function cropImageFile(src, cropPx, componentInstance) {
  const info = await getImageInfo(src);
  const iw = info.width;
  const ih = info.height;

  let x = Math.max(0, Math.floor(cropPx.x));
  let y = Math.max(0, Math.floor(cropPx.y));
  let width = Math.max(1, Math.floor(cropPx.width));
  let height = Math.max(1, Math.floor(cropPx.height));

  if (x + width > iw) width = iw - x;
  if (y + height > ih) height = ih - y;

  const ctx = uni.createCanvasContext('imgProcCanvas', componentInstance);
  ctx.clearRect(0, 0, width, height);
  ctx.drawImage(info.path, x, y, width, height, 0, 0, width, height);
  await waitDraw(ctx);

  return canvasExport('imgProcCanvas', width, height, componentInstance);
}

export function cropNormToPixels(norm, naturalW, naturalH) {
  const x = Math.max(0, Math.min(1, norm.x));
  const y = Math.max(0, Math.min(1, norm.y));
  let w = Math.max(0.05, Math.min(1, norm.w));
  let h = Math.max(0.05, Math.min(1, norm.h));
  if (x + w > 1) w = 1 - x;
  if (y + h > 1) h = 1 - y;

  return {
    x: Math.round(x * naturalW),
    y: Math.round(y * naturalH),
    width: Math.round(w * naturalW),
    height: Math.round(h * naturalH),
  };
}

/** 是否接近全图裁切框 */
export function isFullCrop(norm) {
  return norm.w > 0.98 && norm.h > 0.98 && norm.x < 0.02 && norm.y < 0.02;
}

/**
 * 流水线：翻转 → 旋转（绕中心）→ 裁切
 */
export async function applyCropPipeline(src, options, componentInstance) {
  const { flipH, flipV, rotateDeg, cropNorm } = options;
  let path = src;

  if (flipH || flipV) {
    path = await flipImage(path, flipH, flipV, componentInstance);
  }

  const deg = Math.round(rotateDeg || 0);
  if (deg % 360 !== 0) {
    path = await rotateImageByDegrees(path, deg, componentInstance);
  }

  if (cropNorm && !isFullCrop(cropNorm)) {
    const info = await getImageInfo(path);
    const px = cropNormToPixels(cropNorm, info.width, info.height);
    path = await cropImageFile(path, px, componentInstance);
  }

  return path;
}

export function calcAspectFitRect(containerW, containerH, imgW, imgH) {
  if (!containerW || !containerH || !imgW || !imgH) {
    return { x: 0, y: 0, w: containerW || 0, h: containerH || 0 };
  }
  const cr = containerW / containerH;
  const ir = imgW / imgH;
  let w;
  let h;
  if (ir > cr) {
    w = containerW;
    h = containerW / ir;
  } else {
    h = containerH;
    w = containerH * ir;
  }
  return {
    x: (containerW - w) / 2,
    y: (containerH - h) / 2,
    w,
    h,
  };
}
