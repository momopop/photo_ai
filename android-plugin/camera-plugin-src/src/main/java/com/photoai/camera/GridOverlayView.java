package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 三分法网格线覆盖层。
 *
 * 格线范围严格限定在 setCropRect() 传入的可视画幅矩形内，
 * 避免格线渗入画幅外的黑色遮罩区域。
 * 未调用 setCropRect 时退化为全屏三分法。
 */
public class GridOverlayView extends View {

    private final Paint mPaint = new Paint();
    private RectF mCropRect = null;   // null = 全屏

    public GridOverlayView(Context context) {
        this(context, null);
    }

    public GridOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 默认：半透明白；开启网格时由 setActiveStyle 切换为金黄色
        mPaint.setColor(Color.argb(150, 255, 255, 255));
        mPaint.setStrokeWidth(2f);
        mPaint.setAntiAlias(true);
    }

    /** 网格开启时使用金黄色粗线，与顶部九宫格按钮状态一致 */
    public void setActiveStyle(boolean active) {
        if (active) {
            mPaint.setColor(Color.argb(200, 255, 215, 0)); // #FFD700
            mPaint.setStrokeWidth(2.5f);
        } else {
            mPaint.setColor(Color.argb(150, 255, 255, 255));
            mPaint.setStrokeWidth(2f);
        }
        invalidate();
    }

    /** 设置可视画幅裁切矩形（屏幕坐标）。传 null 退化为全屏。 */
    public void setCropRect(RectF rect) {
        mCropRect = rect;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float l, t, r, b;
        if (mCropRect != null && !mCropRect.isEmpty()) {
            l = mCropRect.left;
            t = mCropRect.top;
            r = mCropRect.right;
            b = mCropRect.bottom;
        } else {
            l = 0; t = 0; r = getWidth(); b = getHeight();
        }

        float w = r - l;
        float h = b - t;

        // 竖线（在裁切区内三等分）
        float x1 = l + w / 3f;
        float x2 = l + w * 2f / 3f;
        canvas.drawLine(x1, t, x1, b, mPaint);
        canvas.drawLine(x2, t, x2, b, mPaint);

        // 横线（在裁切区内三等分）
        float y1 = t + h / 3f;
        float y2 = t + h * 2f / 3f;
        canvas.drawLine(l, y1, r, y1, mPaint);
        canvas.drawLine(l, y2, r, y2, mPaint);
    }
}
