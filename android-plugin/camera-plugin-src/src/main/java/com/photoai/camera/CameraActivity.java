package com.photoai.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * 全屏原生相机（Camera2 API）
 *
 * 布局参考专业相机 Pro 模式。主要特性：
 *   - 顶部栏：返回（左）| 画幅（真正居中）| 九宫格·闪光（右）
 *   - 画幅展开条：固定在顶部栏正下方，默认 3:4
 *   - 画幅外区域：全黑遮罩
 *   - 单指点击画幅内任意位置：触发真实 Camera2 AF + 显示对焦框
 *   - 对焦框右侧上下滑动：调节 EV
 *   - 直方图：固定在当前画幅左上角
 *   - 底部参数条：EV/快门/ISO/WB/对焦，点击展开刻度尺面板
 */
public class CameraActivity extends Activity {

    public static final String EXTRA_FACING          = "facing";
    public static final String EXTRA_FLASH           = "flash";
    public static final String EXTRA_GRID            = "grid";
    public static final String RESULT_PATH           = "photo_path";
    public static final String RESULT_OPTIMIZED_PATH = "optimized_path";

    private static final String TAG = "PhotoAI.Camera";

    // 相机状态
    private static final int STATE_PREVIEW      = 0;
    private static final int STATE_TAP_FOCUS    = 1;   // 点击对焦中（不触发拍照）
    private static final int STATE_WAITING_LOCK = 2;   // 快门前对焦锁（触发拍照）
    private static final int STATE_CAPTURE      = 3;

    private static final int MAX_IMAGES = 2;

    // ── 画幅选项（HW = height/width）────────────────────────────────────────
    // 全屏=0，3:4→HW=4/3，9:16→HW=16/9，1:1→HW=1
    private static final float[]  RATIO_HW    = { 0f, 4f / 3f, 16f / 9f, 1f };
    private static final String[] RATIO_LABEL = { "全屏", "3:4", "9:16", "1:1" };

    // ── 参数 ID ──────────────────────────────────────────────────────────────
    private static final int P_EV      = 0;
    private static final int P_SHUTTER = 1;
    private static final int P_ISO     = 2;
    private static final int P_WB      = 3;
    private static final int P_FOCUS   = 4;
    private static final int P_NONE    = -1;

    private static final String[] EV_VALUES = {
            "-3", "-2.5", "-2", "-1.5", "-1", "-0.5", "0", "+0.5", "+1", "+1.5", "+2", "+2.5", "+3"
    };
    private static final int EV_AUTO_IDX = 6;

    private static final String[] SHUTTER_LABELS = {
            "AUTO", "1/4000", "1/2000", "1/1000", "1/500", "1/250",
            "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s"
    };
    private static final long[] SHUTTER_NS = {
            0L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L,
            8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L,
            125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
    };

    private static final String[] ISO_LABELS  = { "AUTO", "50", "100", "200", "400", "800", "1600", "3200", "6400" };
    private static final int[]    ISO_VALUES  = { 0, 50, 100, 200, 400, 800, 1600, 3200, 6400 };

    private static final String[] WB_LABELS   = { "AWB", "2300K", "3200K", "4000K", "5000K", "5600K", "6500K", "7500K" };
    private static final int[]    WB_KELVIN   = { 0, 2300, 3200, 4000, 5000, 5600, 6500, 7500 };

    private static final String[] FOCUS_LABELS   = { "AF", "∞", "5m", "3m", "2m", "1.5m", "1m", "0.7m", "0.5m", "0.3m", "0.2m" };
    private static final float[]  FOCUS_DIOPTER  = { 0f, 0f, 0.2f, 0.33f, 0.5f, 0.67f, 1f, 1.43f, 2f, 3.33f, 5f };

    // ── Views ────────────────────────────────────────────────────────────────
    private TextureView      mTextureView;
    private View             mShutterFlash;
    private ImageButton      mBtnCapture, mBtnSwitch, mBtnFlash, mBtnGrid;
    private GridOverlayView  mGridView;
    private RatioOverlayView mRatioOverlay;
    private FocusRingView    mFocusRingView;
    private HistogramView    mHistogramView;
    private TextView         mTvZoom;
    private TextView         mBtnRatioToggle;
    private LinearLayout     mRatioDropdown;
    private View             mTopBar;
    private View             mBottomArea;
    private TextView[]       mRatioBtns;       // 4个画幅子按钮
    private FrameLayout      mParamDetail;
    private ParamRulerView   mParamRuler;
    private TextView         mBtnParamAuto;
    private LinearLayout[]   mParamItems;      // 参数条各列
    private TextView         mTvEvVal, mTvShutterVal, mTvIsoVal, mTvWbVal, mTvFocusVal;

    // ── Camera2 ──────────────────────────────────────────────────────────────
    private CameraManager          mCameraManager;
    private String                 mCameraId;
    private CameraDevice           mCameraDevice;
    private CameraCaptureSession   mCaptureSession;
    private CaptureRequest.Builder mPreviewBuilder;
    private ImageReader            mImageReader;
    private Size                   mPreviewSize;
    private Size                   mCaptureSize;
    private int                    mSensorOrientation;
    private Range<Integer>         mIsoRange;
    private Range<Integer>         mEvRange;
    private float                  mMinFocusDist;

    // ── 状态 & 设置 ──────────────────────────────────────────────────────────
    private int     mState     = STATE_PREVIEW;
    private int     mFacing    = CameraCharacteristics.LENS_FACING_BACK;
    private int     mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
    private boolean mShowGrid  = false;
    private boolean mCapturing = false;

    // 画幅
    private int     mRatioIndex    = 1;       // 默认 3:4
    private boolean mRatioMenuOpen = false;

    // 参数当前索引
    private int mEvIdx      = EV_AUTO_IDX;
    private int mShutterIdx = 0;
    private int mIsoIdx     = 0;
    private int mWbIdx      = 0;
    private int mFocusIdx   = 0;
    private int mActiveParam = P_NONE;

    // 变焦
    private float   mMaxZoom      = 1f;
    private float   mCurrentZoom  = 1f;
    private Rect    mSensorRect;
    private ScaleGestureDetector mScaleDetector;

    // EV 拖拽补偿（FocusRingView 产生）
    private float mDragEv = 0f;

    // ── 线程 ─────────────────────────────────────────────────────────────────
    private HandlerThread mBackgroundThread;
    private Handler       mBackgroundHandler;
    private final Handler mUiHandler = new Handler();

    // 直方图定时任务
    private final Runnable mHistogramTask = new Runnable() {
        @Override public void run() {
            if (mTextureView != null && mHistogramView != null
                    && mHistogramView.getVisibility() == View.VISIBLE) {
                Bitmap bmp = mTextureView.getBitmap(80, 45);
                if (bmp != null) { mHistogramView.updateBitmap(bmp); bmp.recycle(); }
            }
            mUiHandler.postDelayed(this, 300);
        }
    };

    // ── 构图提示 & 优化蒙层 ───────────────────────────────────────────────────
    private TextView    mTvHint;
    private View        mOptimizeOverlay;

    // 提示轮换计数器（每个分类独立轮换，避免每次相同文案）
    private final int[] mHintRotate = new int[14];
    private final Runnable mHideHintRunnable = () -> {
        if (mTvHint != null) {
            mTvHint.animate().alpha(0f).setDuration(400)
                    .withEndAction(() -> mTvHint.setVisibility(View.GONE)).start();
        }
    };
    private final Runnable mHintTask = new Runnable() {
        @Override public void run() {
            if (mTextureView == null || mState == STATE_CAPTURE) {
                mUiHandler.postDelayed(this, 2500);
                return;
            }
            Bitmap bmp = mTextureView.getBitmap(160, 90);
            if (bmp != null) {
                String hint = analyzeFrameForHint(bmp);
                bmp.recycle();
                if (hint != null && !hint.isEmpty()) showHint(hint);
            }
            mUiHandler.postDelayed(this, 2500);
        }
    };

    // ══════════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);

        Intent intent = getIntent();
        mFacing   = "front".equals(intent.getStringExtra(EXTRA_FACING))
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        mShowGrid = intent.getBooleanExtra(EXTRA_GRID, false);
        applyFlashExtra(intent.getStringExtra(EXTRA_FLASH));

        bindViews();
        initParamStrip();
        updateFlashIcon();
        updateGridIcon();
        syncRatioUI();
        updateAllParamLabels();

        mScaleDetector = new ScaleGestureDetector(this, new PinchZoomListener());
        mFocusRingView.setScaleDetector(mScaleDetector);
        mFocusRingView.setListener(new FocusRingView.Listener() {
            @Override public void onFocusTap(float x, float y) { doTapFocus(x, y); }
            @Override public void onExposureDelta(float delta) {
                mDragEv = Math.max(-3f, Math.min(3f, mDragEv + delta * 30f));
                applyEvToPreview(Math.round(mDragEv * 2));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        mUiHandler.postDelayed(mHistogramTask, 600);
        mUiHandler.postDelayed(mHintTask, 3000); // 开机3s后开始构图分析
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        mUiHandler.removeCallbacks(mHistogramTask);
        mUiHandler.removeCallbacks(mHintTask);
        mUiHandler.removeCallbacks(mHideHintRunnable);
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && mState == STATE_PREVIEW) {
            onCaptureTapped();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 视图绑定
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        mTextureView    = findViewById(R.id.texture_view);
        mShutterFlash   = findViewById(R.id.shutter_flash);
        mBtnCapture     = findViewById(R.id.btn_capture);
        mBtnSwitch      = findViewById(R.id.btn_switch);
        mBtnFlash       = findViewById(R.id.btn_flash);
        mBtnGrid        = findViewById(R.id.btn_grid);
        mGridView       = findViewById(R.id.grid_view);
        mRatioOverlay   = findViewById(R.id.ratio_overlay);
        mFocusRingView  = findViewById(R.id.focus_ring_view);
        mHistogramView  = findViewById(R.id.histogram_view);
        mTvZoom         = findViewById(R.id.tv_zoom);
        mBtnRatioToggle = (TextView) findViewById(R.id.btn_ratio_toggle);
        mRatioDropdown  = (LinearLayout) findViewById(R.id.ratio_dropdown);
        mTopBar         = findViewById(R.id.top_bar);
        mBottomArea     = findViewById(R.id.bottom_area);
        mParamDetail    = (FrameLayout) findViewById(R.id.param_detail);
        mParamRuler     = (ParamRulerView) findViewById(R.id.param_ruler);
        mBtnParamAuto   = (TextView) findViewById(R.id.btn_param_auto);

        mRatioBtns = new TextView[] {
                (TextView) findViewById(R.id.btn_ratio_full),
                (TextView) findViewById(R.id.btn_ratio_34),
                (TextView) findViewById(R.id.btn_ratio_169),
                (TextView) findViewById(R.id.btn_ratio_11)
        };

        mParamItems = new LinearLayout[] {
                (LinearLayout) findViewById(R.id.param_ev),
                (LinearLayout) findViewById(R.id.param_shutter),
                (LinearLayout) findViewById(R.id.param_iso),
                (LinearLayout) findViewById(R.id.param_wb),
                (LinearLayout) findViewById(R.id.param_focus)
        };

        mTvEvVal       = (TextView) findViewById(R.id.tv_ev_val);
        mTvShutterVal  = (TextView) findViewById(R.id.tv_shutter_val);
        mTvIsoVal      = (TextView) findViewById(R.id.tv_iso_val);
        mTvWbVal       = (TextView) findViewById(R.id.tv_wb_val);
        mTvFocusVal    = (TextView) findViewById(R.id.tv_focus_val);
        mTvHint        = (TextView) findViewById(R.id.tv_hint);
        mOptimizeOverlay = findViewById(R.id.optimize_overlay);

        // ── 按钮事件 ──────────────────────────────────────────────────────
        mBtnCapture.setOnClickListener(v -> onCaptureTapped());
        mBtnSwitch.setOnClickListener(v -> switchCamera());
        mBtnFlash.setOnClickListener(v -> cycleFlash());
        mBtnGrid.setOnClickListener(v -> toggleGrid());
        mTvZoom.setOnClickListener(v -> cycleZoomPreset());
        mBtnRatioToggle.setOnClickListener(v -> toggleRatioMenu());
        mBtnParamAuto.setOnClickListener(v -> resetActiveParam());

        ImageButton btnBack = (ImageButton) findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });

        for (int i = 0; i < mRatioBtns.length; i++) {
            final int idx = i;
            if (mRatioBtns[i] != null) mRatioBtns[i].setOnClickListener(v -> { selectRatio(idx); collapseRatioMenu(); });
        }

        ImageButton btnReset = (ImageButton) findViewById(R.id.btn_reset);
        if (btnReset != null) btnReset.setOnClickListener(v -> resetAllParams());

        // 首次布局完成后，按顶部/底部栏高度更新画幅有效区域与直方图位置
        mRatioOverlay.post(() -> {
            updateRatioPreviewInsets();
            updateHistogramPosition();
        });
        if (mTopBar != null) {
            mTopBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                updateRatioPreviewInsets();
                updateHistogramPosition();
            });
        }
        if (mBottomArea != null) {
            mBottomArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                updateRatioPreviewInsets();
                updateHistogramPosition();
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 直方图位置：固定在画幅左上角
    // ══════════════════════════════════════════════════════════════════════════

    private void updateHistogramPosition() {
        if (mRatioOverlay == null) return;
        RectF crop   = mRatioOverlay.getCropRectF();
        // 直方图固定在裁切区左上角
        if (mHistogramView != null) {
            float margin = dp(8);
            mHistogramView.setX(crop.left + margin);
            mHistogramView.setY(crop.top + margin);
        }
        // 九宫格格线只在裁切区内绘制
        if (mGridView != null) {
            mGridView.setCropRect(crop);
        }
    }

    private void updateRatioPreviewInsets() {
        if (mRatioOverlay == null) return;
        float topInset = mTopBar != null ? mTopBar.getHeight() : 0f;
        if (mRatioDropdown != null && mRatioDropdown.getVisibility() == View.VISIBLE) {
            // 画幅菜单展开时，把下拉条也算进不可用区域，保证 9:16 视觉居中不偏上
            topInset += mRatioDropdown.getHeight();
        }
        float bottomInset = mBottomArea != null ? mBottomArea.getHeight() : 0f;
        mRatioOverlay.setPreviewInsets(topInset, bottomInset);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 画幅菜单
    // ══════════════════════════════════════════════════════════════════════════

    private void toggleRatioMenu() {
        mRatioMenuOpen = !mRatioMenuOpen;
        mRatioDropdown.setVisibility(mRatioMenuOpen ? View.VISIBLE : View.GONE);
        mBtnRatioToggle.setBackgroundResource(
                mRatioMenuOpen ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
        mBtnRatioToggle.setTextColor(mRatioMenuOpen ? 0xFF000000 : 0xFFFFFFFF);
        updateRatioPreviewInsets();
        updateHistogramPosition();
    }

    private void collapseRatioMenu() {
        mRatioMenuOpen = false;
        mRatioDropdown.setVisibility(View.GONE);
        mBtnRatioToggle.setBackgroundResource(R.drawable.bg_pill_inactive);
        mBtnRatioToggle.setTextColor(0xFFFFFFFF);
        updateRatioPreviewInsets();
        updateHistogramPosition();
    }

    private void selectRatio(int idx) {
        mRatioIndex = idx;
        mBtnRatioToggle.setText(RATIO_LABEL[idx]);
        mRatioOverlay.setRatioHW(RATIO_HW[idx]);
        syncRatioUI();
        mRatioOverlay.post(() -> {
            updateRatioPreviewInsets();
            updateHistogramPosition();
        });  // 直方图跟随
    }

    private void syncRatioUI() {
        mBtnRatioToggle.setText(RATIO_LABEL[mRatioIndex]);
        for (int i = 0; i < mRatioBtns.length; i++) {
            if (mRatioBtns[i] == null) continue;
            boolean active = (i == mRatioIndex);
            mRatioBtns[i].setTextColor(active ? 0xFFFFD700 : 0xAAFFFFFF);
            mRatioBtns[i].setTypeface(null,
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        // 初始化画幅遮罩
        mRatioOverlay.setRatioHW(RATIO_HW[mRatioIndex]);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 参数条
    // ══════════════════════════════════════════════════════════════════════════

    private void initParamStrip() {
        for (int i = 0; i < mParamItems.length; i++) {
            final int pid = i;
            if (mParamItems[i] != null) mParamItems[i].setOnClickListener(v -> selectParam(pid));
        }
        if (mParamDetail != null) mParamDetail.setVisibility(View.GONE);
    }

    private void selectParam(int pid) {
        if (mActiveParam == pid) {
            mActiveParam = P_NONE; collapseParamDetail();
        } else {
            mActiveParam = pid; expandParamDetail(pid);
        }
        highlightActiveParam();
    }

    private void expandParamDetail(int pid) {
        String[] vals; int idx;
        switch (pid) {
            case P_EV:      vals = EV_VALUES;      idx = mEvIdx;      break;
            case P_SHUTTER: vals = SHUTTER_LABELS; idx = mShutterIdx; break;
            case P_ISO:     vals = ISO_LABELS;     idx = mIsoIdx;     break;
            case P_WB:      vals = WB_LABELS;      idx = mWbIdx;      break;
            case P_FOCUS:   vals = FOCUS_LABELS;   idx = mFocusIdx;   break;
            default: return;
        }
        mParamRuler.setup(vals, idx);
        mParamRuler.setListener(new ParamRulerView.Listener() {
            @Override public void onIndexChanged(int i) { applyParamLive(pid, i); }
            @Override public void onIndexSettled(int i) { applyParamLive(pid, i); }
        });
        if (mBtnParamAuto != null) mBtnParamAuto.setVisibility(pid == P_EV ? View.GONE : View.VISIBLE);
        if (mParamDetail.getVisibility() != View.VISIBLE) {
            mParamDetail.setVisibility(View.VISIBLE);
            mParamDetail.setAlpha(0f);
            mParamDetail.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void collapseParamDetail() {
        if (mParamDetail == null) return;
        mParamDetail.animate().alpha(0f).setDuration(140)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        mParamDetail.setVisibility(View.GONE);
                        mParamDetail.animate().setListener(null);
                    }
                }).start();
    }

    private void highlightActiveParam() {
        for (int i = 0; i < mParamItems.length; i++) {
            if (mParamItems[i] != null)
                mParamItems[i].setBackgroundResource(i == mActiveParam ? R.drawable.bg_param_active : 0);
        }
    }

    private void applyParamLive(int pid, int idx) {
        switch (pid) {
            case P_EV:
                mEvIdx = idx; mTvEvVal.setText(EV_VALUES[idx]);
                applyEvToPreview(evIdxToStep(idx)); break;
            case P_SHUTTER:
                mShutterIdx = idx; mTvShutterVal.setText(SHUTTER_LABELS[idx]);
                applyShutterIsoToPreview(); break;
            case P_ISO:
                mIsoIdx = idx; mTvIsoVal.setText(ISO_LABELS[idx]);
                applyShutterIsoToPreview(); break;
            case P_WB:
                mWbIdx = idx; mTvWbVal.setText(WB_LABELS[idx]);
                applyWbToPreview(idx); break;
            case P_FOCUS:
                mFocusIdx = idx; mTvFocusVal.setText(FOCUS_LABELS[idx]);
                applyFocusToPreview(idx); break;
        }
        updateAllParamLabels();
    }

    private void resetActiveParam() {
        if (mActiveParam == P_NONE) return;
        int defIdx = (mActiveParam == P_EV) ? EV_AUTO_IDX : 0;
        applyParamLive(mActiveParam, defIdx);
        mParamRuler.setCurrentIndex(defIdx);
    }

    private void resetAllParams() {
        mEvIdx = EV_AUTO_IDX; mShutterIdx = 0; mIsoIdx = 0; mWbIdx = 0; mFocusIdx = 0;
        mDragEv = 0f; mActiveParam = P_NONE;
        collapseParamDetail(); highlightActiveParam(); updateAllParamLabels();
        resetPreviewToAuto();
        if (mFocusRingView != null) mFocusRingView.hideFocus();
    }

    private void updateAllParamLabels() {
        if (mTvEvVal     != null) mTvEvVal.setText(EV_VALUES[mEvIdx]);
        if (mTvShutterVal!= null) mTvShutterVal.setText(SHUTTER_LABELS[mShutterIdx]);
        if (mTvIsoVal    != null) mTvIsoVal.setText(ISO_LABELS[mIsoIdx]);
        if (mTvWbVal     != null) mTvWbVal.setText(WB_LABELS[mWbIdx]);
        if (mTvFocusVal  != null) mTvFocusVal.setText(FOCUS_LABELS[mFocusIdx]);
        setParamColor(mTvEvVal,     mEvIdx      != EV_AUTO_IDX);
        setParamColor(mTvShutterVal,mShutterIdx != 0);
        setParamColor(mTvIsoVal,    mIsoIdx     != 0);
        setParamColor(mTvWbVal,     mWbIdx      != 0);
        setParamColor(mTvFocusVal,  mFocusIdx   != 0);
    }

    private void setParamColor(TextView tv, boolean active) {
        if (tv == null) return;
        tv.setTextColor(active ? 0xFFFFD700 : 0xCCFFFFFF);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 相机参数应用
    // ══════════════════════════════════════════════════════════════════════════

    private void applyEvToPreview(int evStep) {
        if (mPreviewBuilder == null || (mIsoIdx != 0 || mShutterIdx != 0)) return;
        try {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evStep);
            refreshPreview();
        } catch (Exception ignored) {}
    }

    private void applyShutterIsoToPreview() {
        if (mPreviewBuilder == null) return;
        try {
            if (mIsoIdx == 0 && mShutterIdx == 0) {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode);
            } else {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                mPreviewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY,
                        mIsoIdx == 0 ? clampIso(400) : ISO_VALUES[mIsoIdx]);
                mPreviewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                        mShutterIdx == 0 ? 16_666_667L : SHUTTER_NS[mShutterIdx]);
            }
            refreshPreview();
        } catch (Exception ignored) {}
    }

    private void applyWbToPreview(int idx) {
        if (mPreviewBuilder == null) return;
        try {
            if (idx == 0) {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            } else {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                mPreviewBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvin2gains(WB_KELVIN[idx]));
                mPreviewBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            }
            refreshPreview();
        } catch (Exception ignored) {}
    }

    private void applyFocusToPreview(int idx) {
        if (mPreviewBuilder == null) return;
        try {
            if (idx == 0) {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, FOCUS_DIOPTER[idx]);
            }
            refreshPreview();
        } catch (Exception ignored) {}
    }

    private void resetPreviewToAuto() {
        if (mPreviewBuilder == null) return;
        try {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            refreshPreview();
        } catch (Exception ignored) {}
    }

    private void refreshPreview() {
        if (mCaptureSession == null || mPreviewBuilder == null) return;
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 触摸对焦（核心修复）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 单指点击触发真实 Camera2 AF：
     *  1. 限定在画幅显示区域内
     *  2. 先关闭菜单/参数面板
     *  3. 设置 AF 区域 → 重启 Repeating（AF_MODE_AUTO + IDLE 触发）
     *  4. 发送单次 capture（AF_TRIGGER_START）
     *  5. captureCallback 接收 FOCUSED_LOCKED 后回调 onFocusLocked
     */
    private void doTapFocus(float tx, float ty) {
        // ① 限定在当前画幅区域内
        RectF crop = null;
        if (mRatioOverlay != null) {
            crop = mRatioOverlay.getCropRectF();
            if (crop.width() > 0 && !crop.contains(tx, ty)) return;
        }

        // ② 关闭展开中的菜单/面板
        if (mRatioMenuOpen) { collapseRatioMenu(); return; }
        if (mActiveParam != P_NONE) {
            collapseParamDetail(); mActiveParam = P_NONE; highlightActiveParam(); return;
        }

        if (mCaptureSession == null || mSensorRect == null || mCameraDevice == null) return;

        try {
            // ③ 将 View 坐标映射到传感器坐标
            float vw = mTextureView.getWidth(), vh = mTextureView.getHeight();
            float rx, ry;
            if (crop != null && crop.width() > 1f && crop.height() > 1f) {
                rx = (tx - crop.left) / crop.width();
                ry = (ty - crop.top) / crop.height();
            } else {
                rx = tx / vw;
                ry = ty / vh;
            }
            rx = Math.max(0f, Math.min(1f, rx));
            ry = Math.max(0f, Math.min(1f, ry));
            int sw = mSensorRect.width(), sh = mSensorRect.height();
            int sx = (int)(sw * rx), sy = (int)(sh * ry);
            int hw = sw / 12, hh = sh / 12;
            Rect fr = new Rect(
                    Math.max(0, sx - hw), Math.max(0, sy - hh),
                    Math.min(sw, sx + hw), Math.min(sh, sy + hh));
            MeteringRectangle[] regions = {
                    new MeteringRectangle(fr, MeteringRectangle.METERING_WEIGHT_MAX)
            };

            if (mFocusIdx == 0) {  // AF 模式（不是手动对焦）
                // ④ 重置为 AUTO 对焦模式 + 新测光区域（IDLE，不触发）
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);

            // ⑤ 更新 Repeating（让 AF 区域生效，无触发）
            mCaptureSession.setRepeatingRequest(
                    mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);

            if (mFocusIdx == 0) {
                // ⑥ 单次 capture 发出 AF_TRIGGER_START
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_START);
                mState = STATE_TAP_FOCUS;
                mCaptureSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
                // ⑦ 立即将 trigger 归 IDLE，避免后续 repeating 重复触发
                mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "doTapFocus", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 变焦
    // ══════════════════════════════════════════════════════════════════════════

    private float[] mZoomPresets;
    private int     mZoomPresetIdx = 0;

    private void buildZoomPresets() {
        List<Float> list = new ArrayList<>(Arrays.asList(1f));
        if (mMaxZoom >= 2f) list.add(2f);
        if (mMaxZoom >= 5f) list.add(5f);
        mZoomPresets = new float[list.size()];
        for (int i = 0; i < list.size(); i++) mZoomPresets[i] = list.get(i);
    }

    private void cycleZoomPreset() {
        if (mZoomPresets == null || mZoomPresets.length < 2) return;
        mZoomPresetIdx = (mZoomPresetIdx + 1) % mZoomPresets.length;
        applyZoom(mZoomPresets[mZoomPresetIdx]);
    }

    private void applyZoom(float zoom) {
        mCurrentZoom = Math.max(1f, Math.min(zoom, mMaxZoom));
        if (mCaptureSession == null || mSensorRect == null) return;
        try {
            mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, getZoomRect(mCurrentZoom));
            mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException ignored) {}
        updateZoomLabel();
    }

    private Rect getZoomRect(float zoom) {
        int w = (int)(mSensorRect.width()  / zoom);
        int h = (int)(mSensorRect.height() / zoom);
        return new Rect((mSensorRect.width()-w)/2, (mSensorRect.height()-h)/2,
                (mSensorRect.width()+w)/2, (mSensorRect.height()+h)/2);
    }

    private void updateZoomLabel() {
        if (mTvZoom == null) return;
        String t = mCurrentZoom < 10f
                ? String.format("%.1f×", mCurrentZoom)
                : String.format("%.0f×", mCurrentZoom);
        runOnUiThread(() -> mTvZoom.setText(t));
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            float z = Math.max(1f, Math.min(mCurrentZoom * d.getScaleFactor(), mMaxZoom));
            if (Math.abs(z - mCurrentZoom) > 0.02f) applyZoom(z);
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 网格 / 闪光
    // ══════════════════════════════════════════════════════════════════════════

    private void toggleGrid() {
        mShowGrid = !mShowGrid;
        updateGridIcon();
    }

    private void updateGridIcon() {
        if (mBtnGrid != null) {
            // OFF = 纯白，ON = 金黄色
            mBtnGrid.setColorFilter(mShowGrid ? 0xFFFFD700 : 0xFFFFFFFF);
        }
        if (mGridView != null) {
            mGridView.setVisibility(mShowGrid ? View.VISIBLE : View.GONE);
            mGridView.setActiveStyle(mShowGrid);
        }
    }

    private void cycleFlash() {
        int[] modes = {
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH,
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                CameraMetadata.CONTROL_AE_MODE_ON
        };
        int idx = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == mFlashMode) { idx = (i+1) % modes.length; break; }
        mFlashMode = modes[idx];
        if (mIsoIdx == 0 && mShutterIdx == 0 && mPreviewBuilder != null) {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode);
            refreshPreview();
        }
        updateFlashIcon();
    }

    private void applyFlashExtra(String f) {
        if      ("on".equals(f))  mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
        else if ("off".equals(f)) mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON;
        else                      mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
    }

    private void applyFlashToRequest(CaptureRequest.Builder b) {
        if (b == null) return;
        b.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode);
        if (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
    }

    private void updateFlashIcon() {
        if (mBtnFlash == null) return;
        if      (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH) mBtnFlash.setImageResource(R.drawable.ic_flash_on);
        else if (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON)              mBtnFlash.setImageResource(R.drawable.ic_flash_off);
        else                                                                    mBtnFlash.setImageResource(R.drawable.ic_flash_auto);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 切换前后摄
    // ══════════════════════════════════════════════════════════════════════════

    private void switchCamera() {
        mFacing = (mFacing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        mCurrentZoom = 1f; mZoomPresetIdx = 0;
        closeCamera();
        openCamera(mTextureView.getWidth(), mTextureView.getHeight());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SurfaceTexture 监听
    // ══════════════════════════════════════════════════════════════════════════

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(w, h); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { configureTransform(w, h); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    };

    // ══════════════════════════════════════════════════════════════════════════
    // 打开 / 关闭相机
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private void openCamera(int w, int h) {
        try {
            mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            mCameraId = findCameraId(mFacing);
            if (mCameraId == null) { Toast.makeText(this, "找不到摄像头", Toast.LENGTH_SHORT).show(); finish(); return; }

            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mCaptureSize   = chooseMaxSize(map.getOutputSizes(ImageFormat.JPEG));
            mPreviewSize   = chooseBestPreviewSize(map.getOutputSizes(SurfaceTexture.class));
            Integer so     = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = so != null ? so : 90;

            configureTransform(w, h);

            mImageReader = ImageReader.newInstance(
                    mCaptureSize.getWidth(), mCaptureSize.getHeight(), ImageFormat.JPEG, MAX_IMAGES);
            mImageReader.setOnImageAvailableListener(mOnImageAvailable, mBackgroundHandler);

            Float mz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxZoom    = (mz != null && mz > 1f) ? mz : 1f;
            mSensorRect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            mIsoRange   = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            mEvRange    = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Float mfd   = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            mMinFocusDist = mfd != null ? mfd : 0f;

            buildZoomPresets();
            mCurrentZoom = 1f; mZoomPresetIdx = 0;
            updateZoomLabel();

            mCameraManager.openCamera(mCameraId, mDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera", e);
        }
    }

    private void closeCamera() {
        if (mCaptureSession != null) { mCaptureSession.close(); mCaptureSession = null; }
        if (mCameraDevice   != null) { mCameraDevice.close();   mCameraDevice   = null; }
        if (mImageReader    != null) { mImageReader.close();     mImageReader    = null; }
        mState = STATE_PREVIEW;
    }

    private final CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice cam) { mCameraDevice = cam; createPreviewSession(); }
        @Override public void onDisconnected(@NonNull CameraDevice cam) { cam.close(); mCameraDevice = null; }
        @Override public void onError(@NonNull CameraDevice cam, int error) {
            cam.close(); mCameraDevice = null;
            runOnUiThread(() -> { Toast.makeText(CameraActivity.this, "相机错误:" + error, Toast.LENGTH_SHORT).show(); finish(); });
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            st.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface ps = new Surface(st);

            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.addTarget(ps);
            applyFlashToRequest(mPreviewBuilder);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

            mCameraDevice.createCaptureSession(Arrays.asList(ps, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession s) { mCaptureSession = s; startPreview(); }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) { Log.e(TAG, "Session failed"); }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "createPreviewSession", e); }
    }

    private void startPreview() {
        try {
            mState = STATE_PREVIEW;
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            mCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "startPreview", e); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 拍照
    // ══════════════════════════════════════════════════════════════════════════

    private void onCaptureTapped() {
        if (mState != STATE_PREVIEW || mCapturing) return;
        mCapturing = true;
        if (mFocusIdx == 0) lockFocusForCapture(); else captureStillPicture();
    }

    private void lockFocusForCapture() {
        try {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException e) { captureStillPicture(); }
    }

    private void captureStillPicture() {
        try {
            CaptureRequest.Builder cb =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            cb.addTarget(mImageReader.getSurface());

            cb.set(CaptureRequest.JPEG_QUALITY,                     (byte) 100);
            cb.set(CaptureRequest.JPEG_THUMBNAIL_SIZE,              null);
            cb.set(CaptureRequest.NOISE_REDUCTION_MODE,             CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            cb.set(CaptureRequest.EDGE_MODE,                        CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            cb.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            cb.set(CaptureRequest.SHADING_MODE,                     CaptureRequest.SHADING_MODE_HIGH_QUALITY);
            cb.set(CaptureRequest.JPEG_ORIENTATION,                 calcJpegOrientation());

            if (mIsoIdx == 0 && mShutterIdx == 0) {
                applyFlashToRequest(cb);
                cb.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIdxToStep(mEvIdx));
            } else {
                cb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                cb.set(CaptureRequest.SENSOR_SENSITIVITY, mIsoIdx == 0 ? clampIso(400) : ISO_VALUES[mIsoIdx]);
                cb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mShutterIdx == 0 ? 16_666_667L : SHUTTER_NS[mShutterIdx]);
            }
            if (mWbIdx == 0) {
                cb.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            } else {
                cb.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                cb.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvin2gains(WB_KELVIN[mWbIdx]));
                cb.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            }
            if (mFocusIdx == 0) {
                cb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                cb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                cb.set(CaptureRequest.LENS_FOCUS_DISTANCE, FOCUS_DIOPTER[mFocusIdx]);
            }
            if (mSensorRect != null && mCurrentZoom > 1f)
                cb.set(CaptureRequest.SCALER_CROP_REGION, getZoomRect(mCurrentZoom));

            mState = STATE_CAPTURE;
            mCaptureSession.stopRepeating();
            mCaptureSession.capture(cb.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                        @NonNull CaptureRequest r, @NonNull TotalCaptureResult t) { unlockFocus(); }
            }, mBackgroundHandler);

            runOnUiThread(this::animateShutter);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureStillPicture", e);
            mCapturing = false;
        }
    }

    private void unlockFocus() {
        try {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            startPreview();
            mCapturing = false;
        } catch (CameraAccessException e) { Log.e(TAG, "unlockFocus", e); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CaptureCallback（状态机）
    // ══════════════════════════════════════════════════════════════════════════

    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult r) {
            switch (mState) {
                // 点击对焦：等待 AF 锁定，显示对焦框结果，不触发拍照
                case STATE_TAP_FOCUS: {
                    Integer afState = r.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) break;
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) {
                        mState = STATE_PREVIEW;
                        runOnUiThread(() -> { if (mFocusRingView != null) mFocusRingView.onFocusLocked(true); });
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        mState = STATE_PREVIEW;
                        runOnUiThread(() -> { if (mFocusRingView != null) mFocusRingView.onFocusLocked(false); });
                    }
                    break;
                }
                // 拍照前对焦：等待锁定后拍照
                case STATE_WAITING_LOCK: {
                    Integer afState = r.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) { captureStillPicture(); break; }
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        Integer aeState = r.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED)
                            captureStillPicture();
                        else
                            runPrecapture();
                    }
                    break;
                }
            }
        }

        @Override public void onCaptureProgressed(@NonNull CameraCaptureSession s,
                @NonNull CaptureRequest r, @NonNull CaptureResult p) { process(p); }
        @Override public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                @NonNull CaptureRequest r, @NonNull TotalCaptureResult t) { process(t); }
    };

    private void runPrecapture() {
        try {
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mCaptureSession.capture(mPreviewBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { Log.e(TAG, "runPrecapture", e); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 图片保存
    // ══════════════════════════════════════════════════════════════════════════

    private final ImageReader.OnImageAvailableListener mOnImageAvailable = reader -> {
        try (Image img = reader.acquireNextImage()) {
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            saveImage(bytes);
        }
    };

    private void saveImage(byte[] bytes) {
        // 在后台线程上全程处理，UI 线程只做蒙层开关 + finish
        runOnUiThread(() -> {
            if (mOptimizeOverlay != null) mOptimizeOverlay.setVisibility(View.VISIBLE);
        });
        try {
            long ts   = System.currentTimeMillis();
            File extCache = getExternalCacheDir();
            File dir  = new File(extCache != null ? extCache : getCacheDir(), "photoai_camera");
            if (!dir.exists()) dir.mkdirs();

            // ── 原图（按画幅裁切并物理转正，不存相册）─────────────────────────
            byte[] original = (mRatioIndex > 0 && RATIO_HW[mRatioIndex] > 0)
                    ? cropToRatio(bytes, RATIO_HW[mRatioIndex])
                    : cropToRatio(bytes, jpegDisplayRatioHW(bytes)); // 全屏：仅转正、不裁切
            File fOrig = new File(dir, "IMG_PhotoAI_" + ts + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(fOrig)) { fos.write(original); }

            // ── Tier 2：本地 AI 优化（自动色调 + 三分法裁切 + 锐化）─────────
            byte[] optimized = LocalOptimizer.optimize(original);
            File fOpt = new File(dir, "IMG_PhotoAI_" + ts + "_opt.jpg");
            try (FileOutputStream fos = new FileOutputStream(fOpt)) { fos.write(optimized); }

            // ── 回传两条路径，关闭蒙层，返回结果页 ──────────────────────────
            final String origPath = fOrig.getAbsolutePath();
            final String optPath  = fOpt.getAbsolutePath();
            runOnUiThread(() -> {
                if (mOptimizeOverlay != null) mOptimizeOverlay.setVisibility(View.GONE);
                Intent res = new Intent();
                res.putExtra(RESULT_PATH,           origPath);
                res.putExtra(RESULT_OPTIMIZED_PATH, optPath);
                setResult(RESULT_OK, res);
                finish();
            });
        } catch (IOException e) {
            Log.e(TAG, "saveImage", e);
            runOnUiThread(() -> {
                if (mOptimizeOverlay != null) mOptimizeOverlay.setVisibility(View.GONE);
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                mCapturing = false;
            });
        }
    }

    /**
     * 按目标比例（height/width）裁切 JPEG，完整保留竖向显示。
     *
     * 核心原理：
     *  1. 从原始 JPEG 读取 EXIF 旋转角（Camera2 已通过 JPEG_ORIENTATION 写入）。
     *  2. 在「显示坐标系」（已旋转后的画面）里计算裁切区域。
     *  3. 将显示坐标系的裁切矩形映射回「原始像素坐标系」，交给
     *     BitmapRegionDecoder 做局部解码（节省内存）。
     *  4. 对解码出的 Bitmap 做物理旋转，得到正确朝向的最终图。
     *  5. 压缩为 JPEG，不再写 EXIF 旋转标志（像素本身已是正立的）。
     *
     * 这样相册就能直接识别为竖向图，不会再出现横向显示的问题。
     */
    private byte[] cropToRatio(byte[] jpeg, float targetRatioHW) {
        try {
            // ── Step 1: 读取 EXIF 旋转角 ──────────────────────────────────
            int degrees = 0;
            try {
                File tmpExif = File.createTempFile("exif_r", ".jpg", getCacheDir());
                try (FileOutputStream fo = new FileOutputStream(tmpExif)) { fo.write(jpeg); }
                ExifInterface exif = new ExifInterface(tmpExif.getAbsolutePath());
                degrees = exifToDegrees(exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
                tmpExif.delete();
            } catch (Exception ignored) {}

            // ── Step 2: 原始尺寸 & 显示尺寸 ──────────────────────────────
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            int rawW = opts.outWidth, rawH = opts.outHeight;

            // 90°/270° 时宽高互换，得到「显示」尺寸
            int dispW = (degrees == 90 || degrees == 270) ? rawH : rawW;
            int dispH = (degrees == 90 || degrees == 270) ? rawW : rawH;

            // ── Step 3: 在显示坐标系计算裁切区域 ─────────────────────────
            float curHW = (float) dispH / dispW;
            int cW, cH;
            if (curHW > targetRatioHW) { cW = dispW; cH = Math.round(dispW * targetRatioHW); }
            else                        { cH = dispH; cW = Math.round(dispH / targetRatioHW); }
            int dL = (dispW - cW) / 2, dT = (dispH - cH) / 2;
            int dR = dL + cW,          dB = dT + cH;

            // 几乎不需要裁切：无旋转则原样返回；有 EXIF 旋转则仍需物理转正像素
            if (cW >= dispW * 0.99f && cH >= dispH * 0.99f) {
                if (degrees == 0) return jpeg;
                BitmapFactory.Options fullOpts = new BitmapFactory.Options();
                Bitmap full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, fullOpts);
                if (full == null) return jpeg;
                Matrix rm = new Matrix();
                rm.postRotate(degrees);
                Bitmap upright = Bitmap.createBitmap(full, 0, 0, full.getWidth(), full.getHeight(), rm, true);
                full.recycle();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                upright.compress(Bitmap.CompressFormat.JPEG, 97, baos);
                upright.recycle();
                return baos.toByteArray();
            }

            // ── Step 4: 映射到原始像素坐标 ───────────────────────────────
            // degrees=90: display(col=d_x,row=d_y) 对应 raw(col=d_y, row=rawH-d_x-1)
            //   → 裁切 raw_col: [dT, dB)，raw_row: [rawH-dR, rawH-dL)
            Rect rawRect = dispCropToRaw(dL, dT, dR, dB, rawW, rawH, degrees);

            // ── Step 5: 局部解码（节省内存）──────────────────────────────
            BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
            Bitmap patch = dec.decodeRegion(rawRect, null);
            dec.recycle();

            // ── Step 6: 物理旋转到正立方向 ───────────────────────────────
            Bitmap finalBmp;
            if (degrees == 0) {
                finalBmp = patch;
            } else {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                finalBmp = Bitmap.createBitmap(patch, 0, 0, patch.getWidth(), patch.getHeight(), m, true);
                patch.recycle();
            }

            // ── Step 7: 压缩输出（无需 EXIF 旋转标志）───────────────────
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 97, baos);
            finalBmp.recycle();
            return baos.toByteArray();

        } catch (Exception e) {
            return jpeg;
        }
    }

    /**
     * 将显示坐标系的裁切矩形映射回原始（未旋转）像素坐标系。
     * 推导基于各旋转角的像素对应关系：
     *   90°:  display(col,row) ↔ raw(col=row, row=rawH-1-col)
     *  180°:  display(col,row) ↔ raw(col=rawW-1-col, row=rawH-1-row)
     *  270°:  display(col,row) ↔ raw(col=rawW-1-row, row=col)
     */
    private static Rect dispCropToRaw(int dL, int dT, int dR, int dB, int rawW, int rawH, int deg) {
        switch (deg) {
            case  90: return new Rect(dT,        rawH - dR, dB,        rawH - dL);
            case 180: return new Rect(rawW - dR, rawH - dB, rawW - dL, rawH - dT);
            case 270: return new Rect(rawW - dB, dL,        rawW - dT, dR);
            default:  return new Rect(dL,        dT,        dR,        dB);
        }
    }

    private static int exifToDegrees(int exifOrientation) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    /** 从 JPEG 计算显示坐标系下的 height/width（用于全屏模式「只转正不裁切」） */
    private float jpegDisplayRatioHW(byte[] jpeg) {
        try {
            int degrees = 0;
            File tmpExif = File.createTempFile("exif_hw", ".jpg", getCacheDir());
            try (FileOutputStream fo = new FileOutputStream(tmpExif)) { fo.write(jpeg); }
            ExifInterface exif = new ExifInterface(tmpExif.getAbsolutePath());
            degrees = exifToDegrees(exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
            tmpExif.delete();

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            int rawW = opts.outWidth, rawH = opts.outHeight;
            int dispW = (degrees == 90 || degrees == 270) ? rawH : rawW;
            int dispH = (degrees == 90 || degrees == 270) ? rawW : rawH;
            return dispW > 0 ? (float) dispH / dispW : 4f / 3f;
        } catch (Exception e) {
            return 4f / 3f;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 快门动画
    // ══════════════════════════════════════════════════════════════════════════

    private void animateShutter() {
        mShutterFlash.setVisibility(View.VISIBLE);
        mShutterFlash.setAlpha(0.75f);
        ObjectAnimator a = ObjectAnimator.ofFloat(mShutterFlash, "alpha", 0.75f, 0f);
        a.setDuration(180);
        a.setInterpolator(new DecelerateInterpolator());
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) { mShutterFlash.setVisibility(View.GONE); }
        });
        a.start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Center-Crop 变换矩阵（修复黑边）
    // ══════════════════════════════════════════════════════════════════════════

    private void configureTransform(int vw, int vh) {
        if (mPreviewSize == null || vw == 0 || vh == 0) return;
        int bW = mPreviewSize.getWidth(), bH = mPreviewSize.getHeight();
        float cx = vw / 2f, cy = vh / 2f;
        Matrix m = new Matrix();
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        if (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180) {
            float s = Math.max((float) vw / bH, (float) vh / bW);
            m.setScale(s * bH / vw, s * bW / vh, cx, cy);
            if (rot == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        } else {
            float s = Math.max((float) vw / bW, (float) vh / bH);
            m.setScale(s * bW / vw, s * bH / vh, cx, cy);
            if (rot == Surface.ROTATION_270) m.postRotate(180, cx, cy);
        }
        mTextureView.setTransform(m);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════════════════════

    private int calcJpegOrientation() {
        int deg;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:  deg = 90;  break;
            case Surface.ROTATION_180: deg = 180; break;
            case Surface.ROTATION_270: deg = 270; break;
            default:                   deg = 0;   break;
        }
        if (mFacing == CameraCharacteristics.LENS_FACING_FRONT)
            return (mSensorOrientation + deg + 360) % 360;
        else
            return (mSensorOrientation - deg + 360) % 360;
    }

    private int evIdxToStep(int idx) {
        if (mEvRange == null) return 0;
        float ev = Float.parseFloat(EV_VALUES[idx]);
        return Math.round(ev * 2);
    }

    private int clampIso(int iso) {
        if (mIsoRange == null) return iso;
        return Math.max(mIsoRange.getLower(), Math.min(mIsoRange.getUpper(), iso));
    }

    private RggbChannelVector kelvin2gains(int k) {
        float t = (k - 2000f) / (10000f - 2000f);
        return new RggbChannelVector(1.8f - t * 0.8f, 1f, 1f, 0.5f + t * 1.0f);
    }

    private String findCameraId(int facing) throws CameraAccessException {
        for (String id : mCameraManager.getCameraIdList()) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facing) return id;
        }
        return null;
    }

    private Size chooseMaxSize(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    private Size chooseBestPreviewSize(Size[] choices) {
        Size best = null; long bp = 0;
        for (Size s : choices) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                long px = (long) s.getWidth() * s.getHeight();
                if (px > bp) { bp = px; best = s; }
            }
        }
        return best != null ? best : chooseMaxSize(choices);
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try { mBackgroundThread.join(); } catch (InterruptedException ignored) {}
            mBackgroundThread = null; mBackgroundHandler = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 构图提示（纯 Java 规则，无网络，低耗能）
    // ══════════════════════════════════════════════════════════════════════════

    // ── 提示文案池（每类多条，轮换输出） ────────────────────────────────────
    // 索引对应 pickHint() 的分类 ID
    private static final String[][] HINT_POOL = {
        /* 0  欠曝 */ {"画面偏暗，可提升曝光", "光线不足，试试调高EV", "画面较暗，上调曝光补偿"},
        /* 1  过曝 */ {"画面偏亮，注意高光", "略微过曝，可降低EV", "高光溢出，减少曝光补偿"},
        /* 2  逆光 */ {"明显逆光，开启HDR效果更佳", "逆光场景，尝试补光或换角度", "逆光拍摄，增大EV或用闪光灯"},
        /* 3  侧光 */ {"侧光充足，立体感强 ✓", "光线从侧面入射，层次感好", "侧光照明，阴影丰富"},
        /* 4  低对比 */ {"画面较平，可增加对比度", "光线柔和，对比度偏低", "平光场景，构图更显重要"},
        /* 5  高对比 */ {"对比强烈，注意高光与阴影平衡", "光比较大，可适当补光", "明暗对比强，层次分明"},
        /* 6  主体偏左 */ {"主体偏左，右移取景更均衡", "向右平移让主体进入三分区", "主体在左，稍右移改善构图"},
        /* 7  主体偏右 */ {"主体偏右，左移取景更均衡", "向左平移让主体进入三分区", "主体在右，稍左移改善构图"},
        /* 8  主体偏上 */ {"主体偏高，下移半步构图更稳", "画面重心偏高，适当下移", "主体位置偏上，可下移取景"},
        /* 9  主体偏下 */ {"主体偏低，上移可增加天空留白", "重心偏低，向上取景更有张力", "主体在画面底部，尝试上移"},
        /* 10 主体居中 */ {"主体居中，三分构图更有动感", "中心对称构图，试试移至三分点", "尝试将主体移至三分交叉点"},
        /* 11 构图良好 */ {"构图良好 ✓", "三分构图到位 ✓", "主体接近三分点，构图佳 ✓", "构图比例不错 ✓"},
        /* 12 色彩丰富 */ {"色彩丰富，注意主体突出", "高饱和场景，背景可适当虚化", "色彩鲜艳，突出主体更抓眼"},
        /* 13 色调淡雅 */ {"色调淡雅，注重光影构图", "低饱和场景，层次感是关键", "色彩偏淡，三分构图尤为重要"},
    };

    /** 从指定分类中轮换取一条提示，避免每次文案相同 */
    private String pickHint(int category) {
        String[] pool = HINT_POOL[category];
        int idx = mHintRotate[category] % pool.length;
        mHintRotate[category]++;
        return pool[idx];
    }

    /**
     * 多维度分析取景帧，返回一条构图提示。
     *
     * 维度（按优先级）：
     *   ① 亮度（欠曝 / 过曝）
     *   ② 逆光（顶部亮度远高于整体）
     *   ③ 对比度（低 / 高）
     *   ④ 侧光不对称（左右亮度差异大）
     *   ⑤ 主体位置（亮度加权重心 vs 三分法交叉点）
     *   ⑥ 色彩饱和度
     */
    private String analyzeFrameForHint(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w == 0 || h == 0) return "";

        // ── 批量取像素（避免逐像素 JNI 开销）──────────────────────────────
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        long sumX = 0, sumY = 0, totalLum = 0;
        long lumSqSum = 0;
        long lumTop = 0, lumBot = 0, lumLeft = 0, lumRight = 0;
        long satSum = 0;
        int  topCnt = 0, botCnt = 0, leftCnt = 0, rightCnt = 0;

        int midX = w / 2, midY = h / 3; // 逆光判断：上1/3区

        int step = 2;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p   = pixels[y * w + x];
                int r   = (p >> 16) & 0xFF;
                int g   = (p >>  8) & 0xFF;
                int b   =  p        & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;

                sumX    += (long) x * lum;
                sumY    += (long) y * lum;
                totalLum += lum;
                lumSqSum += (long) lum * lum;

                if (y < midY) { lumTop  += lum; topCnt++;   }
                else          { lumBot  += lum; botCnt++;   }
                if (x < midX) { lumLeft += lum; leftCnt++;  }
                else          { lumRight+= lum; rightCnt++; }

                int saturation = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                satSum += saturation;
            }
        }

        if (totalLum == 0) return "";

        long sampleCount  = (long)((h / step) + 1) * ((w / step) + 1);
        float avgLum      = (float) totalLum / sampleCount;
        float avgLumSq    = (float) lumSqSum / sampleCount;
        float variance    = avgLumSq - avgLum * avgLum;
        float stdDev      = (float) Math.sqrt(Math.max(0, variance));  // 对比度指标

        float topAvg    = topCnt   > 0 ? (float) lumTop   / topCnt   : 0;
        float botAvg    = botCnt   > 0 ? (float) lumBot   / botCnt   : 0;
        float leftAvg   = leftCnt  > 0 ? (float) lumLeft  / leftCnt  : 0;
        float rightAvg  = rightCnt > 0 ? (float) lumRight / rightCnt : 0;
        float avgSat    = (float) satSum / sampleCount;

        float cx = (float) sumX / totalLum / w;  // 主体归一化 X（0~1）
        float cy = (float) sumY / totalLum / h;

        // ① 亮度判断
        if (avgLum < 35) return pickHint(0);   // 欠曝
        if (avgLum > 210) return pickHint(1);  // 过曝

        // ② 逆光（上1/3亮度比整体高50%+）
        if (topAvg > avgLum * 1.5f && topAvg > 100) return pickHint(2);

        // ③ 对比度
        if (stdDev < 25) return pickHint(4);   // 低对比（平光）
        if (stdDev > 85) return pickHint(5);   // 高对比（大光比）

        // ④ 侧光不对称（左右差>25%）
        float sideRatio = Math.abs(leftAvg - rightAvg) / (Math.max(leftAvg, rightAvg) + 1f);
        if (sideRatio > 0.25f) return pickHint(3);

        // ⑤ 主体位置（三分法交叉点）
        float minDist = Float.MAX_VALUE;
        for (float tx : new float[]{0.333f, 0.667f}) {
            for (float ty : new float[]{0.333f, 0.667f}) {
                float d = (cx - tx) * (cx - tx) + (cy - ty) * (cy - ty);
                if (d < minDist) minDist = d;
            }
        }
        if (minDist < 0.008f) return pickHint(11); // 构图良好

        if (cx < 0.30f) return pickHint(6);   // 主体偏左
        if (cx > 0.70f) return pickHint(7);   // 主体偏右
        if (cy < 0.28f) return pickHint(8);   // 主体偏上
        if (cy > 0.72f) return pickHint(9);   // 主体偏下
        if (cx > 0.38f && cx < 0.62f
                && cy > 0.38f && cy < 0.62f) return pickHint(10); // 主体居中

        // ⑥ 色彩饱和度辅助提示
        if (avgSat > 60) return pickHint(12); // 色彩丰富
        if (avgSat < 15) return pickHint(13); // 色调淡雅

        return "";
    }

    /** 在取景框下方浮现提示胶囊，3 秒后淡出消失。 */
    private void showHint(final String text) {
        if (mTvHint == null) return;
        mUiHandler.removeCallbacks(mHideHintRunnable);
        mTvHint.setText(text);
        mTvHint.setAlpha(0f);
        mTvHint.setVisibility(View.VISIBLE);
        mTvHint.animate().alpha(1f).setDuration(300).start();
        mUiHandler.postDelayed(mHideHintRunnable, 3000);
    }
}
