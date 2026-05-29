/**
 * 将 PhotoAI-Camera 原生插件与可打包的启动图路径写入 frontend/src/manifest.json
 * 由 integrate-native-camera.ps1 调用
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');
const manifestPath = path.join(repoRoot, 'frontend', 'src', 'manifest.json');

const SPLASH_REL = {
  hdpi: 'nativeResources/android/splash/snappro_ai_hdpi.png',
  xhdpi: 'nativeResources/android/splash/snappro_ai_xhdpi.png',
  xxhdpi: 'nativeResources/android/splash/snappro_ai_xxhdpi.png',
};

function main() {
  if (!fs.existsSync(manifestPath)) {
    console.error('未找到 manifest:', manifestPath);
    process.exit(1);
  }

  const raw = fs.readFileSync(manifestPath, 'utf8');
  const manifest = JSON.parse(raw);

  if (!manifest['app-plus']) {
    console.error('manifest 缺少 app-plus 节点');
    process.exit(1);
  }

  const appPlus = manifest['app-plus'];
  appPlus.nativePlugins = appPlus.nativePlugins || {};
  appPlus.nativePlugins['PhotoAI-Camera'] = {
    version: '1.0.0',
    provider: 'local',
  };

  appPlus.distribute = appPlus.distribute || {};
  appPlus.distribute.android = appPlus.distribute.android || {};
  // 不声明 READ/WRITE_EXTERNAL_STORAGE：
  // 1. 插件写入 getCacheDir()（App 私有目录），无需外部存储权限
  // 2. androidx.core / exifinterface 的 AAR Manifest 会自带这两条，
  //    若 manifest.json 里也声明，云打包 Manifest Merger 会报 duplicated 错误
  appPlus.distribute.android.permissions = [
    '<uses-permission android:name="android.permission.CAMERA"/>',
    '<uses-permission android:name="android.permission.INTERNET"/>',
    '<uses-feature android:name="android.hardware.camera" android:required="true"/>',
    '<uses-feature android:name="android.hardware.camera.autofocus" android:required="false"/>',
  ];
  appPlus.distribute.android.abiFilters = ['armeabi-v7a', 'arm64-v8a'];

  appPlus.distribute.splashscreen = appPlus.distribute.splashscreen || {};
  appPlus.distribute.splashscreen.androidStyle = 'default';
  appPlus.distribute.splashscreen.android = {
    hdpi: SPLASH_REL.hdpi,
    xhdpi: SPLASH_REL.xhdpi,
    xxhdpi: SPLASH_REL.xxhdpi,
  };

  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 4) + '\n', 'utf8');
  console.log('manifest.json 已更新: nativePlugins + 启动图相对路径 + Android 权限');
}

main();
