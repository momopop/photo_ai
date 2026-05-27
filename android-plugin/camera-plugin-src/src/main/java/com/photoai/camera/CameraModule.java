package com.photoai.camera;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;

import com.alibaba.fastjson.JSONObject;
import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * PhotoAI 原生相机 UniModule
 *
 * JS 调用方式：
 *   const camera = uni.requireNativePlugin('PhotoAI-Camera');
 *   camera.takePhoto({ facing: 'back', flash: 'auto', grid: true }, (result) => {
 *     if (result.code === 0) { console.log(result.path); }
 *   });
 */
public class CameraModule extends UniModule {

    private static final int REQUEST_TAKE_PHOTO  = 0x1001;
    private static final int REQUEST_PERMISSIONS = 0x1002;

    // 静态持有回调，避免 Activity 重建丢失
    private static UniJSCallback sPendingCallback;
    private static JSONObject    sPendingOptions;

    // ──────────────────────────────────────────────────────────────────────────
    // 对外暴露的 JS 方法
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 打开原生相机拍照
     *
     * @param options  { facing: 'back'|'front', flash: 'off'|'on'|'auto'|'torch', grid: bool }
     * @param callback result: { code: 0, path: '/absolute/path.jpg' }
     *                         { code: -1, message: 'cancelled' }
     *                         { code: -2, message: 'permission_denied' }
     */
    @UniJSMethod(uiThread = true)
    public void takePhoto(JSONObject options, UniJSCallback callback) {
        sPendingCallback = callback;
        sPendingOptions  = options != null ? options : new JSONObject();

        Activity activity = getHostActivity();
        if (activity == null) {
            invokeError(callback, -3, "activity_null");
            return;
        }

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{ Manifest.permission.CAMERA },
                    REQUEST_PERMISSIONS);
            return;
        }

        launchCamera(activity);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 启动 CameraActivity
    // ──────────────────────────────────────────────────────────────────────────

    private static void launchCamera(Activity activity) {
        JSONObject opts = sPendingOptions != null ? sPendingOptions : new JSONObject();
        Intent intent = new Intent(activity, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_FACING,
                opts.getString("facing") != null ? opts.getString("facing") : "back");
        intent.putExtra(CameraActivity.EXTRA_FLASH,
                opts.getString("flash")  != null ? opts.getString("flash")  : "auto");
        intent.putExtra(CameraActivity.EXTRA_GRID,
                opts.getBooleanValue("grid"));             // 三分法网格线
        activity.startActivityForResult(intent, REQUEST_TAKE_PHOTO);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Activity 回调 (uni-app SDK 会转发给所有 UniModule)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_TAKE_PHOTO) return;

        UniJSCallback cb = sPendingCallback;
        sPendingCallback = null;
        if (cb == null) return;

        if (resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra(CameraActivity.RESULT_PATH);
            JSONObject result = new JSONObject();
            result.put("code", 0);
            result.put("path", path);
            cb.invoke(result);
        } else {
            invokeError(cb, -1, "cancelled");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        Activity activity = getHostActivity();
        if (activity != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera(activity);
        } else {
            invokeError(sPendingCallback, -2, "permission_denied");
            sPendingCallback = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 工具
    // ──────────────────────────────────────────────────────────────────────────

    private Activity getHostActivity() {
        if (mUniSDKInstance == null || mUniSDKInstance.getContext() == null) {
            return null;
        }
        if (mUniSDKInstance.getContext() instanceof Activity) {
            return (Activity) mUniSDKInstance.getContext();
        }
        return null;
    }

    private void invokeError(UniJSCallback cb, int code, String message) {
        if (cb == null) return;
        JSONObject result = new JSONObject();
        result.put("code", code);
        result.put("message", message);
        cb.invoke(result);
    }
}
