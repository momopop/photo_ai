require('dotenv').config();
const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const path = require('path');
const fs = require('fs');

const analyzeRouter = require('./routes/analyze');
const editRouter = require('./routes/edit');
const composeRouter = require('./routes/compose');

const app = express();
const PORT = process.env.PORT || 3000;

// 确保上传目录存在
const uploadDir = path.join(__dirname, '../uploads');
if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });

// 安全头
app.use(helmet({ crossOriginResourcePolicy: { policy: 'cross-origin' } }));

// CORS
app.use(cors({
  origin: '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization'],
}));

// 日志
app.use(morgan('combined'));

// 请求体解析
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

// 限流
const limiter = rateLimit({
  windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS) || 60000,
  max: parseInt(process.env.RATE_LIMIT_MAX) || 100,
  message: { error: '请求过于频繁，请稍后再试' },
  standardHeaders: true,
  legacyHeaders: false,
});
app.use('/api/', limiter);

// 静态文件（上传的图片）
app.use('/uploads', express.static(uploadDir));

// 健康检查
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'PhotoAI Backend',
    timestamp: new Date().toISOString(),
    ai_service: process.env.AI_SERVICE_URL || 'http://localhost:8000',
  });
});

// API 路由
app.use('/api/analyze', analyzeRouter);
app.use('/api/edit', editRouter);
app.use('/api/compose', composeRouter);

// 全局错误处理
app.use((err, req, res, next) => {
  console.error('[Error]', err.message, err.stack);
  const status = err.status || 500;
  res.status(status).json({
    error: err.message || '服务器内部错误',
    code: err.code || 'INTERNAL_ERROR',
  });
});

// 404 处理
app.use((req, res) => {
  res.status(404).json({ error: '接口不存在', path: req.path });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[PhotoAI Backend] 启动成功 http://0.0.0.0:${PORT}`);
  console.log(`[PhotoAI Backend] AI 服务地址: ${process.env.AI_SERVICE_URL || 'http://localhost:8000'}`);
});

module.exports = app;
