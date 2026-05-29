const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

const AI_SERVICE_URL = process.env.AI_SERVICE_URL || 'http://localhost:8000';

/**
 * 将文件和表单字段转发到 Python AI 服务
 */
async function proxyToAI(endpoint, filePath, fields = {}) {
  const form = new FormData();

  // 附加文件
  if (filePath) {
    form.append('file', fs.createReadStream(filePath), {
      filename: 'image.jpg',
      contentType: 'image/jpeg',
    });
  }

  // 附加其他字段
  for (const [key, value] of Object.entries(fields)) {
    if (value !== undefined && value !== null) {
      form.append(key, String(value));
    }
  }

  try {
    const response = await axios.post(`${AI_SERVICE_URL}${endpoint}`, form, {
      headers: form.getHeaders(),
      timeout: 120000,
      maxContentLength: Infinity,
      maxBodyLength: Infinity,
    });
    return response.data;
  } catch (err) {
    const detail = err.response?.data?.detail ?? err.response?.data?.error ?? err.message;
    const message = typeof detail === 'string' ? detail : JSON.stringify(detail);
    const error = new Error(message || 'AI 服务请求失败');
    error.status = err.response?.status || 502;
    throw error;
  }
}

/**
 * 检查 AI 服务连通性
 */
async function checkAIService() {
  try {
    const resp = await axios.get(`${AI_SERVICE_URL}/health`, { timeout: 5000 });
    return resp.data.status === 'ok';
  } catch {
    return false;
  }
}

/**
 * 生成手机/WebView 可访问的图片 URL（经本后端代理 AI 服务的 /uploads）
 * @param {import('express').Request} req
 * @param {string} aiPath 如 /uploads/output_xxx.jpg
 */
function buildClientMediaUrl(req, aiPath) {
  if (!aiPath) return '';
  const path = aiPath.startsWith('/') ? aiPath : `/${aiPath}`;
  const host = req.get('x-forwarded-host') || req.get('host');
  const proto = req.get('x-forwarded-proto') || req.protocol || 'http';
  return `${proto}://${host}/api/media/ai${path}`;
}

module.exports = { proxyToAI, checkAIService, AI_SERVICE_URL, buildClientMediaUrl };
