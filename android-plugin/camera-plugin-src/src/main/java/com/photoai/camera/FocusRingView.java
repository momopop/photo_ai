package com.photoai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 全屏对焦覆盖层：
 *  - 捏合双指 → 缩放（通过 ScaleGestureDetector 传回 Activity）
 *  - 单指点击 → 在点击位置显示对焦框，回调 onFocusTap
 *  - 单指上下拖动（点击后） → 调节曝光补偿，回调 onExposureDelta
 */
public class FocusRingView extends View {

    public interface Listener {
        void onFocusTap(float x, float y);
        void onExposureDelta(float delta);   // 每次 move 产生的增量，范围约 ±0.05
    }

    private ScaleGestureDetector mScaleDetector;
    private Listener             mListener;

    // 对焦框状态
    private float   mFocusX   = -1, mFocusY = -1;
    private boolean mVisible  = false;
    private float   mFocusAlpha = 0f;

    // EV 拖动状态
    private float   mSunY;        // 太阳图标的 Y 坐标（相对 View）
    private float   mEvOffset = 0; // 当前 EV 偏移（用于绘制指示线）
    private boolean mEvMode   = false;  // 是否处于 EV 拖动模式
    private float   mDownX, mDownY;
    private static final float DRAG_THRESHOLD_PX = 18f;

    // Paint
    private final Paint mRingPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEvLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunRay      = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FocusRingView(Context ctx) { this(ctx, null); }
    public FocusRingView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        mRingPaint.setColor(Color.WHITE);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(2f);

        mEvLinePaint.setColor(Color.argb(200, 255, 215, 0));
        mEvLinePaint.setStrokeWidth(1.5f);
        mEvLinePaint.setStyle(Paint.Style.STROKE);

        mSunPaint.setColor(Color.argb(220, 255, 215, 0));
        mSunPaint.setStyle(Paint.Style.FILL);

        mSunRay.setColor(Color.argb(220, 255, 215, 0));
        mSunRay.setStrokeWidth(1.5f);
        mSunRay.setStyle(Paint.Style.STROKE);
    }

    public void setScaleDetector(ScaleGestureDetector d) { mScaleDetector = d; }
    public void setListener(Listener l) { mListener = l; }

    public void hideFocus() {
        mVisible  = false;
        mFocusX   = -1; mFocusY = -1;
        mEvOffset = 0f;
        invalidate();
    }

    /** Activity 在 AF 成功后调用，改变对焦框颜色 */
    public void onFocusLocked(boolean success) {
        mRingPaint.setColor(success ? Color.argb(220, 80, 220, 80) : Color.WHITE);
        invalidate();
        // 2s 后恢复白色并隐藏
        postDelayed(() -> {
            mRingPaint.setColor(Color.WHITE);
            mVisible = false;
            invalidate();
        }, 2000);
    }

    // ── 触摸事件 ──────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mScaleDetector != null) mScaleDetector.onTouchEvent(event);

        if (event.getPointerCount() > 1) return true;  // 多指交给 scale

        float x = event.getX(), y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x; mDownY = y;
                mEvMode = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (mScaleDetector != null && mScaleDetector.isInProgress()) return true;
                float dx = x - mDownX, dy = y - mDownY;
                if (!mEvMode && Math.abs(dy) > DRAG_THRESHOLD_PX && Math.abs(dy) > Math.abs(dx)) {
                    mEvMode = true;
                }
                if (mEvMode && mListener != null) {
                    // 每像素对应约 0.005 EV，上滑增加
                    float delta = (mDownY - y) * 0.005f;
                    mDownY = y;
                    mEvOffset = Math.max(-2f, Math.min(2f, mEvOffset + delta));
                    mSunY = mFocusY - mEvOffset * 60f;
                    mListener.onExposureDelta(delta);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (mScaleDetector != null && mScaleDetector.isInProgress()) return true;
                if (!mEvMode) {
                    // 点击对焦
                    mFocusX   = x; mFocusY = y;
                    mSunY     = y;
                    mEvOffset = 0f;
                    mVisible  = true;
                    mRingPaint.setColor(Color.WHITE);
                    if (mListener != null) mListener.onFocusTap(x, y);
                    invalidate();
                }
                mEvMode = false;
                return true;
        }
        return true;
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mVisible || mFocusX < 0) return;

        float s      = 110f;      // 对焦框边长（放大）
        float hs     = s / 2f;
        float cx     = mFocusX, cy = mFocusY;
        float corner = s * 0.30f;

        // ── 四角对焦框 ──────────────────────────────────────────────
        mRingPaint.setStrokeWidth(2.5f);
        // 左上
        canvas.drawLine(cx - hs, cy - hs, cx - hs + corner, cy - hs, mRingPaint);
        canvas.drawLine(cx - hs, cy - hs, cx - hs, cy - hs + corner, mRingPaint);
        // 右上
        canvas.drawLine(cx + hs - corner, cy - hs, cx + hs, cy - hs, mRingPaint);
        canvas.drawLine(cx + hs, cy - hs, cx + hs, cy - hs + corner, mRingPaint);
        // 左下
        canvas.drawLine(cx - hs, cy + hs - corner, cx - hs, cy + hs, mRingPaint);
        canvas.drawLine(cx - hs, cy + hs, cx - hs + corner, cy + hs, mRingPaint);
        // 右下
        canvas.drawLine(cx + hs, cy + hs - corner, cx + hs, cy + hs, mRingPaint);
        canvas.drawLine(cx + hs, cy + hs, cx + hs - corner, cy + hs, mRingPaint);

        // ── EV 竖线（对焦框右侧 36px） ───────────────────────────────
        float lineX   = cx + hs + 36f;
        float evRange = 130f;
        float lineTop = cy - evRange;
        float lineBtm = cy + evRange;
        canvas.drawLine(lineX, lineTop, lineX, lineBtm, mEvLinePaint);

        // 当前 EV 位置横刻度（稍宽）
        canvas.drawLine(lineX - 12f, mSunY, lineX + 12f, mSunY, mEvLinePaint);

        // ── 太阳图标 ────────────────────────────────────────────────
        float sunX = lineX + 18f;
        float sunR = 9f;
        canvas.drawCircle(sunX, mSunY, sunR, mSunPaint);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float r1 = sunR + 4f, r2 = sunR + 10f;
            canvas.drawLine(
                    sunX + (float) Math.cos(a) * r1,
                    mSunY + (float) Math.sin(a) * r1,
                    sunX + (float) Math.cos(a) * r2,
                    mSunY + (float) Math.sin(a) * r2,
                    mSunRay);
        }

        // EV 值文字（太阳左侧）
        if (Math.abs(mEvOffset) > 0.05f) {
            Paint tvp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tvp.setColor(Color.argb(220, 255, 215, 0));
            tvp.setTextSize(28f);
            tvp.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format("%+.1f", mEvOffset), sunX, mSunY - 18f, tvp);
        }
    }
}
