/**
 * PhotoAI API 封装
 * 统一管理与后端的通信
 */

// ── 原生相机插件（仅 APP-PLUS 生效）──────────────────────────────────────────
// #ifdef APP-PLUS
let _nativeCameraPlugin = null;
function getNativeCameraPlugin() {
  if (_nativeCameraPlugin !== null) return _nativeCameraPlugin;
  try {
    _nativeCameraPlugin = uni.requireNativePlugin('PhotoAI-Camera');
  } catch (e) {
    _nativeCameraPlugin = undefined; // 插件未安装，标记为 undefined
  }
  return _nativeCameraPlugin;
}
// #endif

// 后端地址，H5 可通过环境变量配置，App 直接写服务器 IP
const BASE_URL = (() => {
  // #ifdef H5
  return process.env.VUE_APP_API_URL || 'http://192.168.122.118:3000';
  // #endif
})();

/**
 * 上传文件并发送请求的核心方法（App 环境）
 */
function uploadRequest(url, filePath, formData = {}) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${BASE_URL}${url}`,
      filePath,
      name: 'image',
      formData,
      header: {
        'Accept': 'application/json',
      },
      timeout: 180000, // 大文件（最高50MB原图）上传需要更长超时
      success: (res) => {
        try {
          if (res.statusCode < 200 || res.statusCode >= 300) {
            let msg = `请求失败 (${res.statusCode})`;
            try {
              const errBody = JSON.parse(res.data);
              msg = errBody.error || errBody.detail || msg;
            } catch (_) { /* ignore */ }
            reject(new Error(msg));
            return;
          }
          const data = JSON.parse(res.data);
          if (data.success === true || data.data) {
            resolve(data);
          } else if (data.error) {
            reject(new Error(data.error));
          } else {
            resolve(data);
          }
        } catch (e) {
          reject(new Error('响应解析失败'));
        }
      },
      fail: (err) => {
        reject(new Error(err.errMsg || '网络请求失败'));
      },
    });
  });
}

/**
 * GET 请求
 */
function getRequest(url) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}${url}`,
      method: 'GET',
      timeout: 10000,
      success: (res) => resolve(res.data),
      fail: (err) => reject(new Error(err.errMsg || '网络错误')),
    });
  });
}

// ── plus.camera 拍照降级方法（仅 APP-PLUS 编译） ─────────────────────────────
// #ifdef APP-PLUS
function _takeWithPlusCamera(resolve, reject) {
  try {
    const camera = plus.camera.getCamera();
    camera.captureImage(
      (path) => {
        try {
          resolve(plus.io.convertLocalFileSystemURL(path));
        } catch (e) {
          resolve(path);
        }
      },
      (err) => {
        if (err.code === 4 || (err.message && err.message.toLowerCase().includes('cancel'))) {
          reject(new Error('cancelled'));
        } else {
          reject(new Error('拍照失败: ' + (err.message || err.code)));
        }
      },
      { filename: '_doc/camera/', index: 1, quality: 100, width: 0, height: 0 }
    );
  } catch (e) {
    uni.chooseImage({
      count: 1,
      sourceType: ['camera'],
      sizeType: ['original'],
      success: (res) => resolve(res.tempFilePaths[0]),
      fail: (err) => reject(new Error(err.errMsg?.includes('cancel') ? 'cancelled' : '拍照失败')),
    });
  }
}
// #endif

/**
 * API 方法集合
 */
export const photoAPI = {
  BASE_URL,

  /**
   * 检查服务状态
   */
  checkHealth() {
    return getRequest('/health');
  },

  /**
   * 检查 AI 服务状态
   */
  checkAIHealth() {
    return getRequest('/api/compose/health');
  },

  /**
   * 图像分析（检测 + 构图分析）
   * @param {string} filePath 本地图片路径
   */
  analyzeImage(filePath) {
    return uploadRequest('/api/analyze', filePath);
  },

  /**
   * 修图
   * @param {string} filePath 本地图片路径
   * @param {object} params 修图参数
   */
  editImage(filePath, params = {}) {
    const formData = {
      brightness: String(params.brightness || 0),
      contrast: String(params.contrast || 0),
      saturation: String(params.saturation || 0),
      sharpness: String(params.sharpness || 0),
      denoise: String(params.denoise || false),
      auto_enhance: String(params.auto_enhance || false),
      crop_x: String(params.crop_x || 0),
      crop_y: String(params.crop_y || 0),
      crop_w: String(params.crop_w || 0),
      crop_h: String(params.crop_h || 0),
    };
    return uploadRequest('/api/edit', filePath, formData);
  },

  /**
   * 全自动构图（一键智能处理）
   * @param {string} filePath 本地图片路径
   */
  autoCompose(filePath) {
    return uploadRequest('/api/compose/auto', filePath);
  },

  /**
   * 从相机拍照 —— 优先使用 PhotoAI 原生插件（Camera2），保证最高原图画质
   *
   * 优先级：
   *   APP-PLUS：① PhotoAI 原生插件（Camera2 最高画质）
   *             ② plus.camera（HTML5+ 原生）
   *             ③ uni.chooseImage 兜底
   *   小程序/H5：uni.chooseImage（sizeType:['original']）
   *
   * @param {object} options  { facing:'back'|'front', flash:'auto'|'on'|'off', grid:bool }
   * @returns {Promise<string>} 图片本地绝对路径
   */
  takePhoto(options = {}) {
    return new Promise((resolve, reject) => {
      // #ifdef APP-PLUS
      const plugin = getNativeCameraPlugin();

      if (plugin) {
        // ── 方案一：PhotoAI 原生 Camera2 插件 ────────────────────────────────
        plugin.takePhoto(
          {
            facing: options.facing || 'back',
            flash: options.flash || 'auto',
            grid: options.grid !== false, // 默认显示三分法网格线
          },
          (result) => {
            if (result.code === 0 && result.path) {
              resolve(result.path);
            } else if (result.code === -1) {
              reject(new Error('cancelled'));
            } else if (result.code === -2) {
              reject(new Error('相机权限被拒绝，请在系统设置中开启'));
            } else {
              // 插件出错，降级到 plus.camera
              _takeWithPlusCamera(resolve, reject);
            }
          }
        );
      } else {
        // ── 方案二：HTML5+ plus.camera（插件未安装时的降级方案）──────────────
        _takeWithPlusCamera(resolve, reject);
      }
      // #endif

      // #ifdef MP-WEIXIN || MP-ALIPAY || MP-BAIDU || MP-TOUTIAO || MP-QQ
      uni.chooseImage({
        count: 1,
        sourceType: ['camera'],
        sizeType: ['original'],
        success: (res) => resolve(res.tempFilePaths[0]),
        fail: (err) => {
          if (err.errMsg && err.errMsg.includes('cancel')) reject(new Error('cancelled'));
          else reject(new Error('拍照失败'));
        },
      });
      // #endif

      // #ifdef H5
      uni.chooseImage({
        count: 1,
        sourceType: ['camera'],
        sizeType: ['original'],
        success: (res) => resolve(res.tempFilePaths[0]),
        fail: (err) => {
          if (err.errMsg && err.errMsg.includes('cancel')) reject(new Error('cancelled'));
          else reject(new Error('拍照失败'));
        },
      });
      // #endif
    });
  },

  /**
   * 从相册选择图片 —— 强制原图，不压缩
   * @returns {Promise<string>} 图片本地路径
   */
  chooseFromAlbum() {
    return new Promise((resolve, reject) => {
      // #ifdef APP-PLUS
      // App 端用 plus.gallery 选图，保证原图路径
      try {
        plus.gallery.pick(
          (path) => {
            const absPath = plus.io.convertLocalFileSystemURL(path);
            resolve(absPath);
          },
          (err) => {
            if (err.code === 4 || (err.message && err.message.toLowerCase().includes('cancel'))) {
              reject(new Error('cancelled'));
            } else {
              // gallery 失败时回退 uni API
              uni.chooseImage({
                count: 1,
                sourceType: ['album'],
                sizeType: ['original'],
                success: (res) => resolve(res.tempFilePaths[0]),
                fail: () => reject(new Error('选择图片失败')),
              });
            }
          },
          { filter: 'image', multiple: false, system: false }
        );
      } catch (e) {
        uni.chooseImage({
          count: 1,
          sourceType: ['album'],
          sizeType: ['original'],
          success: (res) => resolve(res.tempFilePaths[0]),
          fail: (err) => reject(new Error(err.errMsg?.includes('cancel') ? 'cancelled' : '选择图片失败')),
        });
      }
      // #endif

      // #ifndef APP-PLUS
      // 小程序 / H5：sizeType 只选 original
      uni.chooseImage({
        count: 1,
        sourceType: ['album'],
        sizeType: ['original'],
        success: (res) => resolve(res.tempFilePaths[0]),
        fail: (err) => {
          if (err.errMsg && err.errMsg.includes('cancel')) {
            reject(new Error('cancelled'));
          } else {
            reject(new Error('选择图片失败'));
          }
        },
      });
      // #endif
    });
  },

  /**
   * 保存图片到相册
   * @param {string} url 图片 URL 或本地路径
   */
  saveToAlbum(url) {
    return new Promise((resolve, reject) => {
      const save = (path) => {
        uni.saveImageToPhotosAlbum({
          filePath: path,
          success: () => resolve(true),
          fail: (err) => reject(new Error('保存失败: ' + err.errMsg)),
        });
      };

      if (url.startsWith('http')) {
        uni.downloadFile({
          url,
          success: (res) => {
            if (res.statusCode === 200) {
              save(res.tempFilePath);
            } else {
              reject(new Error('下载失败'));
            }
          },
          fail: () => reject(new Error('下载图片失败')),
        });
      } else {
        save(url);
      }
    });
  },
};

export default photoAPI;
