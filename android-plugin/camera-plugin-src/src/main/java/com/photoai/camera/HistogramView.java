package com.photoai.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 实时直方图：从 TextureView 采样小图，计算亮度分布并绘制。
 * 外部调用 updateBitmap(bmp) 触发更新（需在主线程）。
 */
public class HistogramView extends View {

    private static final int BINS = 64;

    private final int[]  mLumBins = new int[BINS];
    private final int[]  mRBins   = new int[BINS];
    private final int[]  mGBins   = new int[BINS];
    private final int[]  mBBins   = new int[BINS];
    private int          mMaxLum  = 1;

    private final Paint mBgPaint   = new Paint();
    private final Paint mLumPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  mPath      = new Path();

    public HistogramView(Context ctx) { this(ctx, null); }
    public HistogramView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        mBgPaint.setColor(Color.argb(160, 0, 0, 0));
        mLumPaint.setColor(Color.argb(160, 220, 220, 220));
        mLumPaint.setStyle(Paint.Style.FILL);
        mRPaint.setColor(Color.argb(100, 255, 80,  80));
        mRPaint.setStyle(Paint.Style.FILL);
        mGPaint.setColor(Color.argb(100,  80, 220,  80));
        mGPaint.setStyle(Paint.Style.FILL);
        mBPaint.setColor(Color.argb(100,  80, 140, 255));
        mBPaint.setStyle(Paint.Style.FILL);
        mBorderPaint.setColor(Color.argb(60, 255, 255, 255));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(0.5f);
    }

    /** 主线程调用，传入缩略图（建议 80×45 以内）计算并刷新直方图 */
    public void updateBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int total = w * h;
        int[] pixels = new int[total];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] r = new int[BINS], g = new int[BINS], b = new int[BINS], lum = new int[BINS];
        for (int p : pixels) {
            int ri = (p >> 16) & 0xFF;
            int gi = (p >>  8) & 0xFF;
            int bi =  p        & 0xFF;
            int li = (ri * 299 + gi * 587 + bi * 114) / 1000;
            r[ri * BINS / 256]++;
            g[gi * BINS / 256]++;
            b[bi * BINS / 256]++;
            lum[li * BINS / 256]++;
        }
        int max = 1;
        for (int v : lum) if (v > max) max = v;

        System.arraycopy(r,   0, mRBins,   0, BINS);
        System.arraycopy(g,   0, mGBins,   0, BINS);
        System.arraycopy(b,   0, mBBins,   0, BINS);
        System.arraycopy(lum, 0, mLumBins, 0, BINS);
        mMaxLum = max;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float vw = getWidth(), vh = getHeight();
        canvas.drawRoundRect(0, 0, vw, vh, 4, 4, mBgPaint);

        drawChannel(canvas, mRBins,   vw, vh, mRPaint);
        drawChannel(canvas, mGBins,   vw, vh, mGPaint);
        drawChannel(canvas, mBBins,   vw, vh, mBPaint);
        drawChannel(canvas, mLumBins, vw, vh, mLumPaint);

        canvas.drawRoundRect(0, 0, vw, vh, 4, 4, mBorderPaint);
    }

    private void drawChannel(Canvas canvas, int[] bins, float vw, float vh, Paint paint) {
        float barW = vw / BINS;
        mPath.reset();
        mPath.moveTo(0, vh);
        for (int i = 0; i < BINS; i++) {
            float barH = (float) bins[i] / mMaxLum * vh;
            mPath.lineTo(i * barW, vh - barH);
        }
        mPath.lineTo(vw, vh);
        mPath.close();
        canvas.drawPath(mPath, paint);
    }
}
