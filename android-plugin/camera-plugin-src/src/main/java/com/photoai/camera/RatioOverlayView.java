package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 画幅遮罩层：在预览区两侧或上下绘制半透明黑色遮罩，直观显示当前画幅裁切范围。
 * ratio = 0  → 全幅（无遮罩）
 * ratio > 0  → height/width（竖屏拍摄，比例为照片高/宽）
 */
public class RatioOverlayView extends View {

    private float  mRatioHW = 0f;   // height/width，0 = 不遮罩
    private final Paint mDark = new Paint();
    private final Paint mLine = new Paint();

    public RatioOverlayView(Context context) { this(context, null); }

    public RatioOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDark.setColor(Color.argb(178, 0, 0, 0));  // 70% 黑
        mLine.setColor(Color.argb(128, 255, 255, 255));
        mLine.setStrokeWidth(1.5f);
        mLine.setStyle(Paint.Style.STROKE);
    }

    /** @param ratioHW  height/width；0 = 全幅（取消遮罩） */
    public void setRatioHW(float ratioHW) {
        mRatioHW = ratioHW;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mRatioHW <= 0f) return;

        float vW = getWidth();
        float vH = getHeight();

        // 在竖屏中，照片宽度 = 屏幕宽度
        float cropW = vW;
        float cropH = Math.min(vW * mRatioHW, vH);

        if (cropH >= vH) {
            // 目标比例比屏幕还高（不常见），改为裁宽
            cropH = vH;
            cropW = vH / mRatioHW;
        }

        float left   = (vW - cropW) / 2f;
        float top    = (vH - cropH) / 2f;
        float right  = left + cropW;
        float bottom = top  + cropH;

        // 上下两条遮罩
        if (top > 0) {
            canvas.drawRect(0, 0, vW, top,    mDark);
            canvas.drawRect(0, bottom, vW, vH, mDark);
        }
        // 左右两条遮罩（1:1 或竖屏比例比屏幕更窄时）
        if (left > 0) {
            canvas.drawRect(0,     0, left,  vH, mDark);
            canvas.drawRect(right, 0, vW,    vH, mDark);
        }

        // 裁切框边线
        canvas.drawRect(left, top, right, bottom, mLine);
    }
}
