# PhotoAI Camera — Android 原生插件

基于 **Camera2 API** 实现的 UniApp Android 原生相机插件，替换 uni 自带的相机调用，
实现接近系统相机的 UI 体验和无损原图画质。

---

## 功能特性

| 能力 | 说明 |
|------|------|
| Camera2 全画质拍摄 | JPEG quality=100，无裁切/压缩，最大传感器分辨率 |
| 高质量参数 | `NOISE_REDUCTION_MODE_HIGH_QUALITY` / `EDGE_MODE_HIGH_QUALITY` |
| 前后摄切换 | 界面右下角一键切换 |
| 三档闪光灯 | 自动 / 开 / 关，右上角图标循环切换 |
| 双指捏合变焦 | 修改 `SCALER_CROP_REGION`，不降低感光元件利用率 |
| 点触对焦/测光 | AF + AE 点测光，精准控制曝光 |
| 三分法网格线 | 辅助构图，可通过参数开关 |
| 快门动画 | 白色闪烁反馈 |
| 音量键拍照 | 音量+ / 音量- 均可触发拍摄 |
| EXIF 方向修正 | 自动写入正确旋转信息，后端 OpenCV 不会读取错方向 |

---

## 目录结构

```
android-plugin/
├── camera-plugin-src/               ← Android 库源码（编译为 AAR）
│   ├── build.gradle
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/photoai/camera/
│   │   │   ├── CameraModule.java        UniModule 入口，JS 桥接
│   │   │   ├── CameraActivity.java      Camera2 全屏相机 Activity
│   │   │   ├── AutoFitTextureView.java  防拉伸 TextureView
│   │   │   └── GridOverlayView.java     三分法网格线视图
│   │   └── res/
│   │       ├── layout/activity_camera.xml
│   │       └── drawable/                图标（SVG Vector）
└── nativeplugins/
    └── PhotoAI-Camera/
        └── package.json                 uni-app 插件描述符
```

---

## 编译步骤（Android Studio）

### 前置要求

- Android Studio Hedgehog 或更高版本
- JDK 17+
- HBuilderX 离线 SDK（用于 `uni-app` 相关 jar）

### 1. 获取 uni-app SDK jar

从 HBuilderX 官网下载 **Android 离线 SDK**，解压后将以下 jar 复制到
`android-plugin/libs/`（新建目录）：

```
SDK/libs/uniapp-v8-release.aar   →  android-plugin/libs/
```

或在 `build.gradle` 中修改 `compileOnly fileTree(dir: '../libs', ...)` 指向实际路径。

### 2. 用 Android Studio 打开插件项目

```
File → Open → 选择 android-plugin/camera-plugin-src/
```

若 Gradle 报找不到 SDK，执行：

```powershell
# 在 android-plugin/camera-plugin-src/ 下（Windows 用 gradlew.bat）
.\gradlew.bat assembleRelease
```

### 3. 取出 AAR

编译完成后 AAR 位于：

```
camera-plugin-src/build/outputs/aar/camera-plugin-src-release.aar
```

将其重命名并复制：

```
photoai-camera-release.aar  →  nativeplugins/PhotoAI-Camera/android/PhotoAICamera.aar
```

---

## 集成到 UniApp 项目

### 一键集成（推荐）

在仓库根目录执行（Windows PowerShell）：

```powershell
.\scripts\integrate-native-camera.ps1
```

脚本会自动：编译 AAR → 复制到 `frontend/nativeplugins/` → 启动图迁入工程 → 更新 `manifest.json`。

强制重新编译插件：

```powershell
.\scripts\integrate-native-camera.ps1 -Rebuild
```

### 方式一：HBuilderX 本地插件（手动）

1. 将整个 `nativeplugins/PhotoAI-Camera/` 目录复制到 UniApp 项目根目录下的
   `nativeplugins/` 文件夹：

   ```
   frontend/nativeplugins/PhotoAI-Camera/
   ├── android/
   │   └── PhotoAICamera.aar
   └── package.json
   ```

2. 在 `frontend/src/manifest.json` 的 `app-plus` → `nativePlugins` 节点中声明：

   ```json
   "app-plus": {
     "nativePlugins": {
       "PhotoAI-Camera": {
         "version": "1.0.0",
         "provider": "local"
       }
     }
   }
   ```

3. **必须使用自定义基座**打包运行（标准基座不包含原生插件）：
   - HBuilderX → 运行 → 运行到手机或模拟器 → 制作自定义调试基座
   - 选择自定义基座后重新运行

### 方式二：发布到 DCloud 插件市场

参考 [uni-app 原生插件提交指南](https://nativesupport.dcloud.net.cn/NativePlugin/course/android)。

---

## JS 调用示例

```javascript
// frontend/src/api/photoai.js 已封装，也可直接调用：

// #ifdef APP-PLUS
const cameraPlugin = uni.requireNativePlugin('PhotoAI-Camera');

cameraPlugin.takePhoto(
  {
    facing: 'back',    // 'back'（后置）| 'front'（前置）
    flash:  'auto',    // 'auto' | 'on' | 'off'
    grid:   true,      // 是否显示三分法网格线
  },
  (result) => {
    if (result.code === 0) {
      console.log('照片路径:', result.path); // 绝对路径，直接上传
    } else if (result.code === -1) {
      console.log('用户取消');
    } else if (result.code === -2) {
      uni.showModal({ title: '提示', content: '请在系统设置中开启相机权限' });
    }
  }
);
// #endif
```

---

## 降级策略

`photoai.js` 中的 `takePhoto()` 已实现三级降级：

```
① PhotoAI 原生 Camera2 插件（最高画质）
    ↓ 插件不存在
② plus.camera（HTML5+ 原生）
    ↓ plus.camera 出错
③ uni.chooseImage（兜底，可能有压缩）
```

开发阶段若只使用标准基座，会自动使用 ② 或 ③，不影响功能验证。

---

## 权限配置

在 `frontend/src/manifest.json` 确认已包含：

```json
"app-plus": {
  "distribute": {
    "android": {
      "permissions": [
        "<uses-permission android:name=\"android.permission.CAMERA\"/>",
        "<uses-permission android:name=\"android.permission.INTERNET\"/>"
      ]
    }
  }
}
```

插件在运行时会动态请求 `CAMERA` 权限（Android 6.0+）。

---

## 常见问题

**Q：打包后提示"插件未找到"**  
A：必须使用包含该插件的自定义基座，标准基座无法加载本地原生插件。

**Q：照片横向显示（旋转90°）**  
A：`CameraActivity` 已通过 `JPEG_ORIENTATION` 写入 EXIF，Python 后端的
`image_utils.prepare_image_file` 会处理 EXIF 旋转，请确保该方法已启用。

**Q：部分机型黑屏预览**  
A：检查 `AutoFitTextureView.setAspectRatio` 是否被调用，可在 `openCamera`
完成后添加日志确认 `mPreviewSize`。
