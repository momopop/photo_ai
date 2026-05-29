package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 水平刻度尺控件，用于相机参数调节。
 *
 * 交互：
 *   - 左右拖动滚动刻度尺，中央金色线代表当前值
 *   - 拖动过程中每越过一格即触发 onIndexChanged 回调（实时预览）
 *   - 抬手时触发 onIndexSettled 回调（最终确认）
 *
 * 外观（参考专业相机 Pro 模式）：
 *   - 黑色半透明背景
 *   - 小白色刻度线（每隔5大1级）
 *   - 当前值居中，金色指示线
 *   - 当前值文字显示在刻度尺上方
 *   - 中央两侧若干值显示标签
 */
public class ParamRulerView extends View {

    public interface Listener {
        void onIndexChanged(int index);   // 拖动中实时回调
        void onIndexSettled(int index);   // 抬手确认
    }

    private String[] mValues;
    private int      mIndex   = 0;
    private float    mDragPx  = 0f;
    private float    mLastX;
    private float    STEP_PX;
    private Listener mListener;

    private final Paint mBgPaint     = new Paint();
    private final Paint mTickMinor   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickMajor   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValuePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ParamRulerView(Context ctx) { this(ctx, null); }
    public ParamRulerView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                ctx.getResources().getDisplayMetrics());
        STEP_PX = 22f * dp;

        mBgPaint.setColor(Color.argb(210, 0, 0, 0));

        mTickMinor.setColor(Color.argb(140, 220, 220, 220));
        mTickMinor.setStrokeWidth(1f);

        mTickMajor.setColor(Color.argb(220, 255, 255, 255));
        mTickMajor.setStrokeWidth(1.5f);

        mLabelPaint.setColor(Color.argb(160, 200, 200, 200));
        mLabelPaint.setTextSize(9f * dp);
        mLabelPaint.setTextAlign(Paint.Align.CENTER);

        mCenterPaint.setColor(Color.argb(240, 255, 215, 0));
        mCenterPaint.setStrokeWidth(2f);
        mCenterPaint.setStyle(Paint.Style.STROKE);

        mValuePaint.setColor(Color.WHITE);
        mValuePaint.setTextSize(13f * dp);
        mValuePaint.setTextAlign(Paint.Align.CENTER);
        mValuePaint.setFakeBoldText(true);
    }

    public void setup(String[] values, int initialIndex) {
        mValues = values;
        mIndex  = Math.max(0, Math.min(initialIndex, values.length - 1));
        mDragPx = 0f;
        invalidate();
    }

    public int getCurrentIndex() { return mIndex; }

    public void setCurrentIndex(int idx) {
        if (mValues == null) return;
        mIndex  = Math.max(0, Math.min(idx, mValues.length - 1));
        mDragPx = 0f;
        invalidate();
    }

    public void setListener(Listener l) { mListener = l; }

    // ── 触摸 ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mValues == null) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = e.getX();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx  = mLastX - e.getX();  // 向左拖 dx > 0，值增大
                mLastX    = e.getX();
                mDragPx  += dx;

                // 每越过半个间距，索引 +1/-1
                while (mDragPx >= STEP_PX * 0.5f && mIndex < mValues.length - 1) {
                    mDragPx -= STEP_PX;
                    mIndex++;
                    if (mListener != null) mListener.onIndexChanged(mIndex);
                }
                while (mDragPx <= -STEP_PX * 0.5f && mIndex > 0) {
                    mDragPx += STEP_PX;
                    mIndex--;
                    if (mListener != null) mListener.onIndexChanged(mIndex);
                }
                // 边界夹紧
                if (mIndex == 0 && mDragPx < 0)            mDragPx = 0;
                if (mIndex == mValues.length - 1 && mDragPx > 0) mDragPx = 0;

                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragPx = 0f;
                if (mListener != null) mListener.onIndexSettled(mIndex);
                invalidate();
                return true;
        }
        return false;
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (mValues == null || mValues.length == 0) return;
        float vw = getWidth(), vh = getHeight();
        canvas.drawRect(0, 0, vw, vh, mBgPaint);

        float cx      = vw / 2f;
        float tickBot = vh * 0.92f;

        int visible = (int)(vw / STEP_PX) + 3;
        for (int off = -visible; off <= visible; off++) {
            int idx = mIndex + off;
            if (idx < 0 || idx >= mValues.length) continue;
            float x = cx + off * STEP_PX - mDragPx;
            if (x < -20 || x > vw + 20) continue;

            boolean major = (idx % 5 == 0) || (idx == 0) || (idx == mValues.length - 1);
            float tickTop = major ? tickBot - vh * 0.45f : tickBot - vh * 0.28f;
            Paint tp = major ? mTickMajor : mTickMinor;
            canvas.drawLine(x, tickTop, x, tickBot, tp);

            if (major) {
                canvas.drawText(mValues[idx], x, tickTop - 4f, mLabelPaint);
            }
        }

        // 中央金色指示线（三角箭头朝下）
        canvas.drawLine(cx, 0, cx, tickBot + 2, mCenterPaint);
        // 三角指示头
        android.graphics.Path tri = new android.graphics.Path();
        tri.moveTo(cx - 5f, 2f);
        tri.lineTo(cx + 5f, 2f);
        tri.lineTo(cx, 10f);
        tri.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.argb(240, 255, 215, 0));
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(tri, fill);

        // 当前值文字
        if (mIndex >= 0 && mIndex < mValues.length) {
            canvas.drawText(mValues[mIndex], cx, vh * 0.55f, mValuePaint);
        }
    }
}
