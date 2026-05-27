package com.photoai.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * 全屏原生相机 Activity（Camera2 API）
 *
 * 修复：
 *  - center-crop 全屏预览，消除黑边
 *  - 画幅选择（全幅 / 4:3 / 16:9 / 1:1）
 *  - 变焦比例常驻显示，点击切换预设
 *  - 拍照后自动保存到手机相册（DCIM/PhotoAI）
 */
public class CameraActivity extends Activity {

    // ── Intent Extras ──────────────────────────────────────────────────────────
    public static final String EXTRA_FACING = "facing";
    public static final String EXTRA_FLASH  = "flash";
    public static final String EXTRA_GRID   = "grid";
    public static final String RESULT_PATH  = "photo_path";

    private static final String TAG = "PhotoAI.Camera";
    private static final int    STATE_PREVIEW      = 0;
    private static final int    STATE_WAITING_LOCK = 1;
    private static final int    STATE_CAPTURE      = 2;
    private static final int    MAX_IMAGES         = 2;

    // 画幅：height/width 比值（竖向拍摄，值越大图片越高）
    // 0 = 全幅（不裁切），其余均为 height/width
    private static final float[] RATIO_HW     = { 0f, 4f/3f, 16f/9f, 1f };
    private static final String[] RATIO_LABEL = { "全幅", "4:3", "16:9", "1:1" };

    // ── Views ──────────────────────────────────────────────────────────────────
    private TextureView       mTextureView;
    private View              mShutterFlash;
    private ImageButton       mBtnCapture;
    private ImageButton       mBtnSwitch;
    private ImageButton       mBtnFlash;
    private GridOverlayView   mGridView;
    private RatioOverlayView  mRatioOverlay;
    private TextView          mTvZoom;
    private TextView[]        mRatioBtns;

    // ── Camera2 ───────────────────────────────────────────────────────────────
    private CameraManager          mCameraManager;
    private String                 mCameraId;
    private CameraDevice           mCameraDevice;
    private CameraCaptureSession   mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private ImageReader            mImageReader;
    private Size                   mPreviewSize;
    private Size                   mCaptureSize;

    // ── 状态 ───────────────────────────────────────────────────────────────────
    private int     mState       = STATE_PREVIEW;
    private int     mFacing      = CameraCharacteristics.LENS_FACING_BACK;
    private int     mFlashMode   = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
    private boolean mShowGrid    = false;
    private boolean mCapturing   = false;
    private int     mRatioIndex  = 1;   // 默认 4:3

    // ── 变焦 ───────────────────────────────────────────────────────────────────
    private float mMaxZoom     = 1f;
    private float mCurrentZoom = 1f;
    private Rect  mSensorRect;
    private ScaleGestureDetector mScaleDetector;

    // ── 预设变焦档位（1×, 2×，超过 3× 才加 0.5× 档） ─────────────────────────
    private float[] mZoomPresets;
    private int     mZoomPresetIdx = 0;

    // ── 线程 / Handler ────────────────────────────────────────────────────────
    private HandlerThread mBackgroundThread;
    private Handler       mBackgroundHandler;
    private final Handler mUiHandler = new Handler();

    // ──────────────────────────────────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        Intent intent = getIntent();
        String facing = intent.getStringExtra(EXTRA_FACING);
        mFacing = "front".equals(facing)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        mShowGrid = intent.getBooleanExtra(EXTRA_GRID, false);
        applyFlashExtra(intent.getStringExtra(EXTRA_FLASH));

        mTextureView  = findViewById(R.id.texture_view);
        mShutterFlash = findViewById(R.id.shutter_flash);
        mBtnCapture   = findViewById(R.id.btn_capture);
        mBtnSwitch    = findViewById(R.id.btn_switch);
        mBtnFlash     = findViewById(R.id.btn_flash);
        mGridView     = findViewById(R.id.grid_view);
        mRatioOverlay = findViewById(R.id.ratio_overlay);
        mTvZoom       = findViewById(R.id.tv_zoom);

        mRatioBtns = new TextView[]{
                (TextView) findViewById(R.id.btn_ratio_full),
                (TextView) findViewById(R.id.btn_ratio_43),
                (TextView) findViewById(R.id.btn_ratio_169),
                (TextView) findViewById(R.id.btn_ratio_11)
        };

        mGridView.setVisibility(mShowGrid ? View.VISIBLE : View.GONE);

        mBtnCapture.setOnClickListener(v -> onCaptureTapped());
        mBtnSwitch.setOnClickListener(v -> switchCamera());
        mBtnFlash.setOnClickListener(v -> cycleFlash());

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // 画幅按钮
        for (int i = 0; i < mRatioBtns.length; i++) {
            final int idx = i;
            mRatioBtns[i].setOnClickListener(v -> selectRatio(idx));
        }
        updateRatioUI();

        // 变焦显示：点击循环切换预设档位
        mTvZoom.setOnClickListener(v -> cycleZoomPreset());

        // 捏合变焦
        mScaleDetector = new ScaleGestureDetector(this, new PinchZoomListener());

        // 触摸对焦
        mTextureView.setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP
                    && !mScaleDetector.isInProgress()) {
                manualFocus(event.getX(), event.getY());
            }
            return true;
        });

        updateFlashIcon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && mState == STATE_PREVIEW) {
            onCaptureTapped();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SurfaceTexture 监听
    // ──────────────────────────────────────────────────────────────────────────

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            openCamera(w, h);
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            configureTransform(w, h);
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    };

    // ──────────────────────────────────────────────────────────────────────────
    // 打开 / 关闭相机
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        try {
            mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            mCameraId = findCameraId(mFacing);
            if (mCameraId == null) {
                Toast.makeText(this, "找不到摄像头", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map  = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            mCaptureSize = chooseMaxSize(map.getOutputSizes(ImageFormat.JPEG));
            mPreviewSize = chooseBestPreviewSize(map.getOutputSizes(SurfaceTexture.class));

            // ── 关键：TextureView 已是 match_parent，configureTransform 负责 center-crop ──
            configureTransform(width, height);

            mImageReader = ImageReader.newInstance(
                    mCaptureSize.getWidth(), mCaptureSize.getHeight(),
                    ImageFormat.JPEG, MAX_IMAGES);
            mImageReader.setOnImageAvailableListener(mOnImageAvailable, mBackgroundHandler);

            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxZoom    = (maxZoom != null && maxZoom > 1f) ? maxZoom : 1f;
            mSensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            buildZoomPresets();
            mCurrentZoom = 1f;
            mZoomPresetIdx = 0;
            updateZoomLabel();

            mCameraManager.openCamera(mCameraId, mDeviceStateCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private void closeCamera() {
        if (mCaptureSession != null) { mCaptureSession.close(); mCaptureSession = null; }
        if (mCameraDevice   != null) { mCameraDevice.close();   mCameraDevice   = null; }
        if (mImageReader    != null) { mImageReader.close();     mImageReader    = null; }
        mState = STATE_PREVIEW;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CameraDevice 状态回调
    // ──────────────────────────────────────────────────────────────────────────

    private final CameraDevice.StateCallback mDeviceStateCallback
            = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            createPreviewSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close(); mCameraDevice = null;
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close(); mCameraDevice = null;
            runOnUiThread(() -> {
                Toast.makeText(CameraActivity.this,
                        "相机错误: " + error, Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    };

    // ──────────────────────────────────────────────────────────────────────────
    // 预览 Session
    // ──────────────────────────────────────────────────────────────────────────

    private void createPreviewSession() {
        try {
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            st.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(st);

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(previewSurface);
            applyFlashToRequest(mPreviewRequestBuilder);

            mCameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            startPreview();
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Session configure failed");
                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createPreviewSession", e);
        }
    }

    private void startPreview() {
        try {
            mState = STATE_PREVIEW;
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreview", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 拍照
    // ──────────────────────────────────────────────────────────────────────────

    private void onCaptureTapped() {
        if (mState != STATE_PREVIEW || mCapturing) return;
        mCapturing = true;
        lockFocus();
    }

    private void lockFocus() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            captureStillPicture();
        }
    }

    private void captureStillPicture() {
        try {
            CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            captureBuilder.set(CaptureRequest.JPEG_QUALITY,                          (byte) 100);
            captureBuilder.set(CaptureRequest.JPEG_THUMBNAIL_SIZE,                   null);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            captureBuilder.set(CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            captureBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            captureBuilder.set(CaptureRequest.SHADING_MODE,
                    CaptureRequest.SHADING_MODE_HIGH_QUALITY);

            applyFlashToRequest(captureBuilder);

            if (mSensorRect != null && mCurrentZoom > 1f) {
                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                        getZoomRect(mCurrentZoom));
            }
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());

            mState = STATE_CAPTURE;
            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            unlockFocus();
                        }
                    }, mBackgroundHandler);

            runOnUiThread(this::animateShutter);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureStillPicture", e);
            mCapturing = false;
        }
    }

    private void unlockFocus() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
            startPreview();
            mCapturing = false;
        } catch (CameraAccessException e) {
            Log.e(TAG, "unlockFocus", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CaptureCallback
    // ──────────────────────────────────────────────────────────────────────────

    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
            if (mState == STATE_WAITING_LOCK) {
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                if (afState == null) {
                    captureStillPicture();
                } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState
                        || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        captureStillPicture();
                    } else {
                        runPrecaptureSequence();
                    }
                }
            }
        }
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession s,
                                        @NonNull CaptureRequest r,
                                        @NonNull CaptureResult p) { process(p); }
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                                       @NonNull CaptureRequest r,
                                       @NonNull TotalCaptureResult t) { process(t); }
    };

    private void runPrecaptureSequence() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mCaptureSession.capture(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "runPrecaptureSequence", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ImageReader：保存图片 + 保存相册
    // ──────────────────────────────────────────────────────────────────────────

    private final ImageReader.OnImageAvailableListener mOnImageAvailable
            = reader -> {
        try (Image image = reader.acquireNextImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            saveImage(bytes);
        }
    };

    private void saveImage(byte[] bytes) {
        try {
            String filename = "IMG_PhotoAI_" + System.currentTimeMillis() + ".jpg";

            // 1. 写入 App 私有缓存（供 uni-app 上传用）
            File cacheDir = new File(getCacheDir(), "photoai_camera");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File cacheFile = new File(cacheDir, filename);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(bytes);
            }

            // 2. 按选定画幅裁切
            if (mRatioIndex > 0 && RATIO_HW[mRatioIndex] > 0) {
                bytes = cropToRatio(bytes, RATIO_HW[mRatioIndex]);
                // 更新缓存文件为裁切后版本
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(bytes);
                }
            }

            // 3. 保存到手机相册（DCIM/PhotoAI）
            saveToGallery(bytes, filename);

            // 4. 返回绝对路径给 uni-app
            Intent result = new Intent();
            result.putExtra(RESULT_PATH, cacheFile.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();

        } catch (IOException e) {
            Log.e(TAG, "saveImage failed", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "保存失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
            mCapturing = false;
        }
    }

    /** 保存到手机相册，不需要 WRITE_EXTERNAL_STORAGE 权限（API 29+）或使用 MediaScanner */
    private void saveToGallery(byte[] bytes, String filename) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/PhotoAI");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.getContentUri("external"), cv);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(bytes);
                    }
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                }
            } else {
                // API 21-28：写到 App 自有外部目录，无需额外权限
                File extDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (extDir == null) return;
                if (!extDir.exists()) extDir.mkdirs();
                File outFile = new File(extDir, filename);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(bytes);
                }
                android.media.MediaScannerConnection.scanFile(
                        this,
                        new String[]{ outFile.getAbsolutePath() },
                        new String[]{ "image/jpeg" },
                        null
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "saveToGallery failed", e);
            // 不因相册保存失败而中断主流程
        }
    }

    /**
     * 用 BitmapRegionDecoder 按画幅比例裁切 JPEG（高效，不需要全量解码）。
     * @param ratioHW 目标 height/width（竖屏）
     */
    private byte[] cropToRatio(byte[] jpegBytes, float ratioHW) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
            int imgW = opts.outWidth;
            int imgH = opts.outHeight;

            // 当前图像的实际方向：若 Camera2 JPEG_ORIENTATION 使像素已旋转，则 imgH > imgW（竖屏）
            float currentHW = (float) imgH / imgW;

            int cropW, cropH;
            if (currentHW > ratioHW) {
                // 图像比目标更高，裁上下
                cropW = imgW;
                cropH = Math.round(imgW * ratioHW);
            } else {
                // 图像比目标更宽，裁左右
                cropH = imgH;
                cropW = Math.round(imgH / ratioHW);
            }

            int left = (imgW - cropW) / 2;
            int top  = (imgH - cropH) / 2;

            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(
                    jpegBytes, 0, jpegBytes.length, false);
            Bitmap cropped = decoder.decodeRegion(
                    new Rect(left, top, left + cropW, top + cropH), null);
            decoder.recycle();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 98, baos);
            cropped.recycle();
            return baos.toByteArray();

        } catch (Exception e) {
            Log.e(TAG, "cropToRatio failed", e);
            return jpegBytes;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 画幅选择
    // ──────────────────────────────────────────────────────────────────────────

    private void selectRatio(int idx) {
        mRatioIndex = idx;
        updateRatioUI();
        mRatioOverlay.setRatioHW(RATIO_HW[idx]);
    }

    private void updateRatioUI() {
        for (int i = 0; i < mRatioBtns.length; i++) {
            mRatioBtns[i].setBackgroundResource(
                    i == mRatioIndex
                            ? R.drawable.bg_ratio_btn_selected
                            : R.drawable.bg_ratio_btn);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 切换前后摄
    // ──────────────────────────────────────────────────────────────────────────

    private void switchCamera() {
        mFacing = (mFacing == CameraCharacteristics.LENS_FACING_BACK)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        mCurrentZoom   = 1f;
        mZoomPresetIdx = 0;
        closeCamera();
        openCamera(mTextureView.getWidth(), mTextureView.getHeight());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 闪光灯
    // ──────────────────────────────────────────────────────────────────────────

    private void cycleFlash() {
        int[] modes = {
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH,
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                CameraMetadata.CONTROL_AE_MODE_ON
        };
        int idx = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == mFlashMode) { idx = (i + 1) % modes.length; break; }
        }
        mFlashMode = modes[idx];
        applyFlashToRequest(mPreviewRequestBuilder);
        try {
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (Exception ignored) {}
        updateFlashIcon();
    }

    private void applyFlashExtra(String flash) {
        if ("on".equals(flash))         mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
        else if ("off".equals(flash))   mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON;
        else                            mFlashMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
    }

    private void applyFlashToRequest(CaptureRequest.Builder builder) {
        if (builder == null) return;
        builder.set(CaptureRequest.CONTROL_AE_MODE, mFlashMode);
        if (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON) {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        }
    }

    private void updateFlashIcon() {
        if (mBtnFlash == null) return;
        if      (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            mBtnFlash.setImageResource(R.drawable.ic_flash_on);
        else if (mFlashMode == CameraMetadata.CONTROL_AE_MODE_ON)
            mBtnFlash.setImageResource(R.drawable.ic_flash_off);
        else
            mBtnFlash.setImageResource(R.drawable.ic_flash_auto);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 触摸点对焦 / 测光
    // ──────────────────────────────────────────────────────────────────────────

    private void manualFocus(float touchX, float touchY) {
        if (mCaptureSession == null || mSensorRect == null) return;
        try {
            float ratioX = touchX / mTextureView.getWidth();
            float ratioY = touchY / mTextureView.getHeight();
            int sensorX = (int) (mSensorRect.width() * ratioX);
            int sensorY = (int) (mSensorRect.height() * ratioY);
            int halfW   = mSensorRect.width()  / 10;
            int halfH   = mSensorRect.height() / 10;

            Rect focusRect = new Rect(
                    Math.max(0, sensorX - halfW), Math.max(0, sensorY - halfH),
                    Math.min(mSensorRect.width(), sensorX + halfW),
                    Math.min(mSensorRect.height(), sensorY + halfH));

            MeteringRectangle[] regions = new MeteringRectangle[]{
                    new MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)
            };
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.capture(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "manualFocus", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 变焦：捏合 + 预设档位切换
    // ──────────────────────────────────────────────────────────────────────────

    private void buildZoomPresets() {
        // 构建档位：至少有 1×；如果 maxZoom >= 2 则加 2×；以此类推
        List<Float> presets = new ArrayList<>();
        presets.add(1f);
        if (mMaxZoom >= 2f) presets.add(2f);
        if (mMaxZoom >= 5f) presets.add(5f);
        if (mMaxZoom >= 10f) presets.add(10f);
        mZoomPresets = new float[presets.size()];
        for (int i = 0; i < presets.size(); i++) mZoomPresets[i] = presets.get(i);
    }

    /** 点击变焦标签：循环切换预设档位 */
    private void cycleZoomPreset() {
        if (mZoomPresets == null || mZoomPresets.length == 0) return;
        mZoomPresetIdx = (mZoomPresetIdx + 1) % mZoomPresets.length;
        applyZoom(mZoomPresets[mZoomPresetIdx]);
    }

    private void applyZoom(float zoom) {
        mCurrentZoom = Math.max(1f, Math.min(zoom, mMaxZoom));
        if (mCaptureSession == null || mSensorRect == null) return;
        try {
            mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                    getZoomRect(mCurrentZoom));
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException ignored) {}
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        if (mTvZoom == null) return;
        String text = mCurrentZoom < 10f
                ? String.format("%.1f×", mCurrentZoom)
                : String.format("%.0f×", mCurrentZoom);
        runOnUiThread(() -> mTvZoom.setText(text));
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float newZoom = Math.max(1f, Math.min(mCurrentZoom * factor, mMaxZoom));
            if (Math.abs(newZoom - mCurrentZoom) > 0.02f) {
                applyZoom(newZoom);
                // 同步 preset 索引
                if (mZoomPresets != null) {
                    for (int i = 0; i < mZoomPresets.length - 1; i++) {
                        if (mCurrentZoom < (mZoomPresets[i] + mZoomPresets[i+1]) / 2f) {
                            mZoomPresetIdx = i; break;
                        }
                        mZoomPresetIdx = mZoomPresets.length - 1;
                    }
                }
            }
            return true;
        }
    }

    private Rect getZoomRect(float zoom) {
        int w = (int) (mSensorRect.width()  / zoom);
        int h = (int) (mSensorRect.height() / zoom);
        int x = (mSensorRect.width()  - w) / 2;
        int y = (mSensorRect.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 快门动画
    // ──────────────────────────────────────────────────────────────────────────

    private void animateShutter() {
        mShutterFlash.setVisibility(View.VISIBLE);
        mShutterFlash.setAlpha(1f);
        ObjectAnimator anim = ObjectAnimator.ofFloat(mShutterFlash, "alpha", 1f, 0f);
        anim.setDuration(200);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                mShutterFlash.setVisibility(View.GONE);
            }
        });
        anim.start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Center-Crop 变换矩阵（修复黑边核心）
    //
    // 原理：相机 HAL 在 SurfaceTexture 输出时已将预览旋转为正确的竖屏朝向。
    // TextureView 是 match_parent（全屏）。默认情况下，内容被等比拉伸到
    // TextureView 尺寸；这里用 Matrix 做 center-crop 缩放，使内容填满屏幕
    // 而不出现黑边（宽方向少量裁切）。
    //
    //   bW = mPreviewSize.width（传感器横向）
    //   bH = mPreviewSize.height（传感器纵向）
    //   有效竖屏预览尺寸 = bH × bW（宽×高）
    //   s = max(vW/bH, vH/bW)
    //   scaleX = s × bH/vW    （center-crop 后宽度略超出屏幕，两侧各裁一点）
    //   scaleY = s × bW/vH    （center-crop 后高度恰好填满屏幕）
    // ──────────────────────────────────────────────────────────────────────────

    private void configureTransform(int viewW, int viewH) {
        if (mPreviewSize == null || viewW == 0 || viewH == 0) return;

        int bW = mPreviewSize.getWidth();    // e.g. 1920（横向分辨率）
        int bH = mPreviewSize.getHeight();   // e.g. 1080（纵向分辨率）
        float cx = viewW / 2f;
        float cy = viewH / 2f;

        Matrix matrix = new Matrix();
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            // 竖屏：HAL 已旋转，有效尺寸为 bH（宽）× bW（高）
            float s = Math.max((float) viewW / bH, (float) viewH / bW);
            matrix.setScale(s * bH / viewW, s * bW / viewH, cx, cy);
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180, cx, cy);
        } else {
            // 横屏
            float s = Math.max((float) viewW / bW, (float) viewH / bH);
            matrix.setScale(s * bW / viewW, s * bH / viewH, cx, cy);
            if (rotation == Surface.ROTATION_270) matrix.postRotate(180, cx, cy);
        }

        mTextureView.setTransform(matrix);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────────────────────────────────

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
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight()
                    > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 选取最适合的预览分辨率：
     * 优先选取分辨率接近 1920×1080（全高清）且不超过它的最大尺寸；
     * 如果全部超出则退而求其次取最大的。
     */
    private Size chooseBestPreviewSize(Size[] choices) {
        Size best = null;
        long bestPixels = 0;
        for (Size s : choices) {
            // 限制在全高清范围内，避免浪费 GPU 带宽
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                long px = (long) s.getWidth() * s.getHeight();
                if (px > bestPixels) { bestPixels = px; best = s; }
            }
        }
        if (best != null) return best;
        // 退化：返回最大可用尺寸
        return chooseMaxSize(choices);
    }

    private int getJpegOrientation() {
        try {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(mCameraId);
            Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) return 0;
            int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (deviceRotation) {
                case Surface.ROTATION_0:   degrees = 0;   break;
                case Surface.ROTATION_90:  degrees = 90;  break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }
            if (mFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                return (360 - ((sensorOrientation + degrees) % 360)) % 360;
            } else {
                return (sensorOrientation - degrees + 360) % 360;
            }
        } catch (CameraAccessException e) {
            return 0;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 后台线程
    // ──────────────────────────────────────────────────────────────────────────

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try { mBackgroundThread.join(); } catch (InterruptedException ignored) {}
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
    }
}
