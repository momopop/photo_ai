package com.photoai.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 纯端侧构图优化器（无需网络、无外部模型）
 *
 * 三步流水线:
 *   1. 自动色调  — 直方图按 2%~98% 拉伸，逐通道去灰提亮
 *   2. 三分法智能裁切 — 亮度加权重心 → 将主体平移至最近三分交叉点
 *   3. 轻量 USM 锐化 — 3×3 卷积核，避免过锐
 *
 * 性能策略：
 *   - 先将长边缩至 MAX_SIDE（1920px），在缩略图上处理再压缩输出
 *   - 所有像素操作走 getPixels / setPixels 批量接口，避免逐像素 JNI 开销
 */
public class LocalOptimizer {

    /** 处理阶段缩放上限（降低内存压力，色调/裁切/锐化在此尺寸运行） */
    private static final int MAX_SIDE     = 1920;
    /** 保存阶段：将优化图放大回的目标长边（接近但不超过原图，避免比原图还模糊） */
    private static final int SAVE_SIDE    = 3264;
    private static final int JPEG_QUALITY = 95;

    /**
     * 优化入口（在后台线程调用）
     *
     * @param jpeg 已按画幅裁切、方向正确的原始 JPEG bytes
     * @return     优化后的 JPEG bytes；失败时原样返回
     */
    public static byte[] optimize(byte[] jpeg) {
        try {
            // ── 读取 EXIF，解码后先物理旋转到正立（与 cropToRatio 输出一致）────
            int exifDegrees = readExifDegrees(jpeg);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);

            int rawW = opts.outWidth, rawH = opts.outHeight;
            // 采样按旋转后的显示尺寸计算
            int dispW = (exifDegrees == 90 || exifDegrees == 270) ? rawH : rawW;
            int dispH = (exifDegrees == 90 || exifDegrees == 270) ? rawW : rawH;
            int longer = Math.max(dispW, dispH);
            int sample = 1;
            while (longer / (sample * 2) > MAX_SIDE) sample *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (src == null) return jpeg;

            src = orientBitmap(src, exifDegrees);

            // 精细缩放（inSampleSize 只能 2 的幂次，再做一次精确缩放）
            int w = src.getWidth(), h = src.getHeight();
            if (Math.max(w, h) > MAX_SIDE) {
                float scale = (float) MAX_SIDE / Math.max(w, h);
                src = Bitmap.createScaledBitmap(src, (int)(w * scale), (int)(h * scale), true);
            }

            // ── Step 1: 自动色调 ──────────────────────────────────────────────
            Bitmap toned = autoTone(src);
            src.recycle();

            // ── Step 2: 三分法智能裁切 ────────────────────────────────────────
            Bitmap cropped = smartCrop(toned);
            if (cropped != toned) toned.recycle();

            // ── Step 3: 轻量锐化 ──────────────────────────────────────────────
            Bitmap sharpened = sharpen(cropped);
            if (sharpened != cropped) cropped.recycle();

            // ── Step 4: 还原分辨率（双线性放大回接近原图尺寸，避免比原图模糊）─
            // 目标：保持不超过原图实际显示尺寸（dispW x dispH），以 SAVE_SIDE 为上限
            int targetSaveSide = Math.min(SAVE_SIDE, Math.max(dispW, dispH));
            int sw = sharpened.getWidth(), sh = sharpened.getHeight();
            if (Math.max(sw, sh) < targetSaveSide) {
                float upScale = (float) targetSaveSide / Math.max(sw, sh);
                int newW = (int)(sw * upScale);
                int newH = (int)(sh * upScale);
                Bitmap upscaled = Bitmap.createScaledBitmap(sharpened, newW, newH, true);
                sharpened.recycle();
                sharpened = upscaled;
            }

            // ── 编码输出（高品质） ────────────────────────────────────────────
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            sharpened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            sharpened.recycle();
            return baos.toByteArray();

        } catch (Exception e) {
            return jpeg;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 1：自动色调（直方图拉伸）
    // ══════════════════════════════════════════════════════════════════════════

    private static Bitmap autoTone(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int n = w * h;
        int[] px = new int[n];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // 逐通道直方图
        int[] histR = new int[256], histG = new int[256], histB = new int[256];
        for (int p : px) {
            histR[(p >> 16) & 0xFF]++;
            histG[(p >>  8) & 0xFF]++;
            histB[ p        & 0xFF]++;
        }

        // 找 2% ~ 98% 的分位点
        int lo2 = n / 50, hi2 = n - lo2;
        int[] loR = {0}, hiR = {255}, loG = {0}, hiG = {255}, loB = {0}, hiB = {255};
        findPercentile(histR, lo2, hi2, loR, hiR);
        findPercentile(histG, lo2, hi2, loG, hiG);
        findPercentile(histB, lo2, hi2, loB, hiB);

        // 如果各通道差异很小（<5），色调本已正常，跳过拉伸避免过度处理
        if (hiR[0] - loR[0] < 10 && hiG[0] - loG[0] < 10 && hiB[0] - loB[0] < 10)
            return src;

        int[] mapR = buildStretchMap(loR[0], hiR[0]);
        int[] mapG = buildStretchMap(loG[0], hiG[0]);
        int[] mapB = buildStretchMap(loB[0], hiB[0]);

        for (int i = 0; i < n; i++) {
            int p = px[i];
            int a =  (p >> 24) & 0xFF;
            px[i] = (a << 24)
                  | (mapR[(p >> 16) & 0xFF] << 16)
                  | (mapG[(p >>  8) & 0xFF] <<  8)
                  |  mapB[ p        & 0xFF];
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void findPercentile(int[] hist, int lo2, int hi2, int[] loOut, int[] hiOut) {
        int sum = 0;
        boolean loFound = false;
        for (int i = 0; i < 256; i++) {
            sum += hist[i];
            if (!loFound && sum >= lo2) { loOut[0] = i; loFound = true; }
            if (sum >= hi2)             { hiOut[0] = i; break; }
        }
    }

    private static int[] buildStretchMap(int lo, int hi) {
        int[] map = new int[256];
        float range = Math.max(1, hi - lo);
        for (int i = 0; i < 256; i++) {
            map[i] = Math.max(0, Math.min(255, Math.round((i - lo) * 255f / range)));
        }
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 2：三分法智能裁切
    // ══════════════════════════════════════════════════════════════════════════

    private static Bitmap smartCrop(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();

        // 下采样（步长=4）快速计算亮度加权重心
        long sumX = 0, sumY = 0, totalLum = 0;
        int step = 4;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p   = px[y * w + x];
                int lum = ((p >> 16 & 0xFF) * 299
                         + (p >>  8 & 0xFF) * 587
                         + (p       & 0xFF) * 114) / 1000;
                sumX += (long) x * lum;
                sumY += (long) y * lum;
                totalLum += lum;
            }
        }
        if (totalLum == 0) return src;

        float subjectX = (float) sumX / totalLum;  // 主体像素 X
        float subjectY = (float) sumY / totalLum;
        float rx = subjectX / w;                    // 归一化 0~1
        float ry = subjectY / h;

        // 找最近三分法交叉点
        float targetRx = nearestThird(rx);
        float targetRy = nearestThird(ry);

        // 主体已在三分点附近（±12%），无需裁切
        if (Math.abs(rx - targetRx) < 0.12f && Math.abs(ry - targetRy) < 0.12f) return src;

        // 保留 85% 的画面（裁掉 15%），让主体向目标点偏移
        float keepW = w * 0.85f;
        float keepH = h * 0.85f;

        // 裁切框左上角：使主体在裁切框内处于 targetRx / targetRy 位置
        float left = subjectX - keepW * targetRx;
        float top  = subjectY - keepH * targetRy;
        left = Math.max(0, Math.min(w - keepW, left));
        top  = Math.max(0, Math.min(h - keepH, top));

        return Bitmap.createBitmap(src, (int) left, (int) top, (int) keepW, (int) keepH);
    }

    private static float nearestThird(float v) {
        return Math.abs(v - 0.333f) < Math.abs(v - 0.667f) ? 0.333f : 0.667f;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 3：轻量 USM 锐化（3×3 Laplacian 增强）
    // ══════════════════════════════════════════════════════════════════════════

    private static Bitmap sharpen(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 3 || h < 3) return src;

        int[] in = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);
        int[] out = new int[in.length];

        // 核：[0,-1,0; -1,5,-1; 0,-1,0]（适度锐化）
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i  = y * w + x;
                int c  = in[i];
                int ct = in[i - w]; int cb = in[i + w];
                int cl = in[i - 1]; int cr = in[i + 1];

                int r = sharpChannel(c, ct, cb, cl, cr, 16);
                int g = sharpChannel(c, ct, cb, cl, cr,  8);
                int b = sharpChannel(c, ct, cb, cl, cr,  0);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        // 边缘行列直接复制
        for (int x = 0; x < w; x++) {
            out[x]           = in[x];
            out[(h-1)*w + x] = in[(h-1)*w + x];
        }
        for (int y = 0; y < h; y++) {
            out[y * w]       = in[y * w];
            out[y * w + w-1] = in[y * w + w-1];
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    private static int sharpChannel(int c, int ct, int cb, int cl, int cr, int shift) {
        int vc = (c  >> shift) & 0xFF;
        int vt = (ct >> shift) & 0xFF;
        int vb = (cb >> shift) & 0xFF;
        int vl = (cl >> shift) & 0xFF;
        int vr = (cr >> shift) & 0xFF;
        // 适度增强：权重 5 而非更高，避免噪点放大
        return Math.max(0, Math.min(255, 5 * vc - vt - vb - vl - vr));
    }

    // ── EXIF 方向（与 CameraActivity.cropToRatio 保持一致）────────────────────

    private static int readExifDegrees(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            return exifToDegrees(exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int exifToDegrees(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    private static Bitmap orientBitmap(Bitmap bmp, int degrees) {
        if (degrees == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        bmp.recycle();
        return out;
    }
}
