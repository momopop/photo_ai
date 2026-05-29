package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 画幅遮罩层：全幅以外区域绘制纯黑遮罩，保证画幅显示区域清晰可辨。
 *
 * ratioHW = 0  → 全屏（不遮罩）
 * ratioHW > 0  → 目标画幅的 height/width（竖屏拍摄时照片高宽比）
 *
 * 典型值：
 *   3:4  → 4/3  ≈ 1.333
 *   9:16 → 16/9 ≈ 1.778
 *   1:1  → 1.0
 */
public class RatioOverlayView extends View {

    private float     mRatioHW = 0f;
    private float     mTopInset = 0f;
    private float     mBottomInset = 0f;
    private final Paint mBlack = new Paint();
    private final Paint mFrame = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RatioOverlayView(Context ctx) { this(ctx, null); }
    public RatioOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        mBlack.setColor(Color.BLACK);            // 全黑遮罩
        mBlack.setStyle(Paint.Style.FILL);

        mFrame.setColor(Color.argb(120, 255, 255, 255));
        mFrame.setStyle(Paint.Style.STROKE);
        mFrame.setStrokeWidth(1.5f);
    }

    /** @param ratioHW  height/width；0 = 全屏（取消遮罩） */
    public void setRatioHW(float ratioHW) {
        mRatioHW = ratioHW;
        invalidate();
    }

    /** 设置取景区上下保留区域（例如顶部栏、底部控制栏） */
    public void setPreviewInsets(float topInset, float bottomInset) {
        mTopInset = Math.max(0f, topInset);
        mBottomInset = Math.max(0f, bottomInset);
        invalidate();
    }

    /**
     * 返回当前画幅在 View 坐标中的矩形（裁切显示区域）。
     * 如果尚未布局（宽高=0），返回零矩形。
     */
    public RectF getCropRectF() {
        int vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0) return new RectF(0, 0, vw, vh);
        float contentTop = Math.min(vh, mTopInset);
        float contentBottom = Math.max(contentTop, vh - mBottomInset);
        float contentH = contentBottom - contentTop;
        if (contentH <= 0f) return new RectF(0, 0, vw, vh);
        if (mRatioHW <= 0f) return new RectF(0, contentTop, vw, contentBottom);

        float cropW = vw;
        float cropH = vw * mRatioHW;
        if (cropH > contentH) {
            cropH = contentH;
            cropW = contentH / mRatioHW;
        }
        float l = (vw - cropW) / 2f;
        float t = contentTop + (contentH - cropH) / 2f;
        return new RectF(l, t, l + cropW, t + cropH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mRatioHW <= 0f) return;

        float vw = getWidth(), vh = getHeight();
        // 与 getCropRectF / 九宫格 / 直方图共用同一矩形（在顶栏与底栏之间垂直居中）
        RectF crop = getCropRectF();
        float l = crop.left, t = crop.top, r = crop.right, b = crop.bottom;

        if (t > 1f) canvas.drawRect(0, 0, vw, t, mBlack);
        if (b < vh - 1f) canvas.drawRect(0, b, vw, vh, mBlack);
        if (l > 1f) canvas.drawRect(0, 0, l, vh, mBlack);
        if (r < vw - 1f) canvas.drawRect(r, 0, vw, vh, mBlack);

        canvas.drawRect(l, t, r, b, mFrame);
    }
}
