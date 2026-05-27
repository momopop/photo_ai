package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 三分法网格线覆盖层，帮助构图。
 */
public class GridOverlayView extends View {

    private final Paint mPaint = new Paint();

    public GridOverlayView(Context context) {
        this(context, null);
    }

    public GridOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(Color.argb(80, 255, 255, 255));  // 半透明白色
        mPaint.setStrokeWidth(1.5f);
        mPaint.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 竖线
        canvas.drawLine(w / 3f, 0, w / 3f, h, mPaint);
        canvas.drawLine(w * 2f / 3f, 0, w * 2f / 3f, h, mPaint);

        // 横线
        canvas.drawLine(0, h / 3f, w, h / 3f, mPaint);
        canvas.drawLine(0, h * 2f / 3f, w, h * 2f / 3f, mPaint);
    }
}
