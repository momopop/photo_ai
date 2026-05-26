/**
 * PhotoAI API 封装
 * 统一管理与后端的通信
 */

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
      timeout: 60000,
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
   * 从相机拍照
   * @returns {Promise<string>} 图片临时路径
   */
  takePhoto() {
    return new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sourceType: ['camera'],
        sizeType: ['original', 'compressed'],
        success: (res) => resolve(res.tempFilePaths[0]),
        fail: (err) => {
          if (err.errMsg && err.errMsg.includes('cancel')) {
            reject(new Error('cancelled'));
          } else {
            reject(new Error('拍照失败'));
          }
        },
      });
    });
  },

  /**
   * 从相册选择图片
   * @returns {Promise<string>} 图片临时路径
   */
  chooseFromAlbum() {
    return new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sourceType: ['album'],
        sizeType: ['original', 'compressed'],
        success: (res) => resolve(res.tempFilePaths[0]),
        fail: (err) => {
          if (err.errMsg && err.errMsg.includes('cancel')) {
            reject(new Error('cancelled'));
          } else {
            reject(new Error('选择图片失败'));
          }
        },
      });
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
