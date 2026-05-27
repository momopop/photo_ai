# PhotoAI - AI 自动构图与修图移动端 App

> 基于 UniApp + Node.js + Python (FastAPI + YOLOv8 + MediaPipe + OpenCV) 构建的完整 AI 摄影助手

## 架构总览

```
前端（UniApp Vue3）
   ↓ HTTP / uploadFile
后端（Node.js / Express API 网关）
   ↓ HTTP multipart
AI 模块（Python / FastAPI）
   ├── 图像检测（YOLOv8 + MediaPipe）
   ├── 构图算法（三分法 + 黄金比例 + 多维评分）
   └── 修图引擎（OpenCV + PIL 自动增强）
```

## 目录结构

```
PhotoAI/
├── frontend/              # UniApp 前端
│   └── src/
│       ├── pages/
│       │   ├── home/      # 首页（拍照/选图入口）
│       │   ├── result/    # 构图分析结果页
│       │   └── editor/    # 修图编辑器页
│       ├── components/    # 公共组件（SliderItem 等）
│       ├── api/           # API 封装
│       ├── store/         # Pinia 状态管理
│       └── App.vue
├── backend/               # Node.js API 网关
│   └── src/
│       ├── routes/        # 路由（analyze / edit / compose）
│       ├── middlewares/   # 中间件（upload / aiProxy）
│       └── server.js
├── ai-service/            # Python AI 服务
│   ├── main.py            # FastAPI 主入口
│   └── modules/
│       ├── detector.py    # YOLOv8 + MediaPipe 检测
│       ├── composition.py # 构图分析与建议
│       └── editor.py      # OpenCV + PIL 修图引擎
├── scripts/               # 启动脚本
├── docker-compose.yml     # 容器编排
└── README.md
```

## 快速启动

### 方式一：Docker Compose（推荐）

```bash
# 构建并启动所有服务
docker-compose up --build

# 后台运行
docker-compose up -d --build
```

服务地址：
- AI 服务 API 文档：http://localhost:8000/docs
- 后端 API：http://localhost:3000
- 健康检查：http://localhost:3000/api/compose/health

### 方式二：本地开发

#### 环境要求
- Python 3.11+
- Node.js 18+
- npm 9+

#### 1. 初始化 AI 服务环境

```powershell
# Windows PowerShell
.\scripts\setup-ai-env.ps1
```

```bash
# macOS / Linux
cd ai-service
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

#### 2. 启动 AI 服务

```bash
cd ai-service
# Windows
.\venv\Scripts\python -m uvicorn main:app --reload --port 8000
# macOS/Linux
./venv/bin/uvicorn main:app --reload --port 8000
```

#### 3. 启动 Node.js 后端

```bash
cd backend
cp .env.example .env
npm install
npm run dev
```

#### 4. 启动 UniApp 前端

```bash
cd frontend
npm install

# H5 浏览器模式（开发调试）
npm run dev:h5
# 访问 http://localhost:8080

# 微信小程序
npm run dev:mp-weixin

# App（需要 HBuilderX）
npm run dev:app
```

#### Windows 一键启动

```powershell
.\scripts\start-dev.ps1
```

## 核心功能说明

### 1. 构图分析（7 维度评分）

| 维度 | 说明 | 权重 |
|------|------|------|
| 三分法 | 主体是否落在三分交叉点 | 25% |
| 视觉平衡 | 亮度重心是否居中 | 20% |
| 主体布置 | 主体大小/位置/留白 | 15% |
| 对称性 | 左右对称程度 | 10% |
| 水平线 | 地平线是否在三分位 | 10% |
| 引导线 | 线条是否引导视线 | 10% |
| 层次感 | 前景背景清晰度差异 | 10% |

### 2. 目标检测

- **YOLOv8n**：检测 80 类通用目标（人、动物、交通工具等）
- **MediaPipe Face Detection**：精确人脸检测 + 关键点
- **MediaPipe Pose**：33 个人体关键点检测
- **Fallback**：当 YOLO 不可用时自动切换 OpenCV 人脸检测

### 3. 裁剪方案生成

自动生成 5 种裁剪方案：
- 主体聚焦（去除干扰背景）
- 三分法最优裁剪
- 黄金比例裁剪（1.618:1）
- 1:1 方形（社交媒体）
- 16:9 宽屏（影视感）

### 4. 修图参数

| 参数 | 范围 | 说明 |
|------|------|------|
| 亮度 | -100~+100 | 整体曝光调整 |
| 对比度 | -100~+100 | 明暗对比增强 |
| 饱和度 | -100~+100 | 色彩鲜艳程度 |
| 锐化 | -100~+100 | 边缘清晰度（负值=柔化） |
| 降噪 | 开/关 | NLMeans 降噪算法 |
| 自动增强 | 开/关 | 灰世界白平衡 + CLAHE + 去雾 |

## API 文档

### POST /api/analyze
上传图片进行检测 + 构图分析

**Request:** `multipart/form-data`
- `image`: 图片文件

**Response:**
```json
{
  "success": true,
  "data": {
    "detection": {
      "objects": [{"label": "person", "confidence": 0.92, "bbox": {...}}],
      "faces": [{"confidence": 0.98, "bbox": {...}}],
      "primary_subject": {"type": "face", "bbox": {...}}
    },
    "composition": {
      "total_score": 72.5,
      "scores": {"thirds": 68, "symmetry": 45, ...},
      "suggestions": ["将主体移至三分法交叉点..."],
      "crop_proposals": [...],
      "composition_type": "三分法构图"
    }
  }
}
```

### POST /api/edit
修图接口

**Request:** `multipart/form-data`
- `image`: 图片文件
- `brightness`: 亮度 (-100~100)
- `contrast`: 对比度 (-100~100)
- `saturation`: 饱和度 (-100~100)
- `sharpness`: 锐化 (-100~100)
- `denoise`: 降噪 (true/false)
- `auto_enhance`: 自动增强 (true/false)
- `crop_x/y/w/h`: 裁剪坐标（像素）

### POST /api/compose/auto
全自动构图（一键处理）

返回：检测结果 + 构图分析 + 修图建议 + 优化预览图

## 移动端配置

### 修改服务器地址

编辑 `frontend/src/api/photoai.js`：

```javascript
// 修改为实际服务器 IP
return 'http://192.168.1.100:3000'; // 你的后端地址
```

### Android 原生相机插件（一键集成）

在仓库根目录执行：

```powershell
.\scripts\integrate-native-camera.ps1
```

脚本会编译 AAR、复制到 `frontend/nativeplugins/`、将启动图迁入工程相对路径、更新 `manifest.json`。详见 [android-plugin/README.md](android-plugin/README.md)。

集成后须在 HBuilderX 中 **制作自定义调试基座** 再真机运行（标准基座不含本地插件）。

### App 打包（HBuilderX）

1. 在 HBuilderX 中打开 `frontend` 目录
2. 修改 `src/manifest.json` 中的 `appid`
3. 若使用原生相机插件，先执行 `.\scripts\integrate-native-camera.ps1`
4. 运行 → 发行 → 原生 App 打包（勾选使用原生插件）

### 微信小程序

1. 修改 `src/pages.json` 中的 `mp-weixin.appid`（微信公众平台申请的小程序 AppID，不是 IP）。
2. 修改 `src/api/photoai.js` 中的 `BASE_URL` 为可访问的后端地址（真机调试需 HTTPS 合法域名或开发环境勾选「不校验合法域名」）。
3. **静态资源目录**：`tabBar` 等引用的图片必须放在 **`src/static/`** 下（与 `pages.json` 同级），例如本项目的 `src/static/images/tab-*.png`。放在项目根目录的 `frontend/static/` **不会**打进 `mp-weixin` 产物，会导致「app.json iconPath 未找到」。
4. 执行 `npm run dev:mp-weixin` 后，用微信开发者工具 **导入目录** `frontend/dist/dev/mp-weixin`；发行构建则用 `dist/build/mp-weixin`。
5. 若仍报图标未找到：删除开发者工具缓存、重新执行编译后再导入。

## 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | UniApp (Vue 3 + Pinia + Composition API) |
| 后端网关 | Node.js + Express + Multer |
| AI 服务 | Python + FastAPI + asyncio |
| 目标检测 | YOLOv8n (Ultralytics) |
| 人脸/姿态 | MediaPipe |
| 修图引擎 | OpenCV + PIL |
| 容器化 | Docker + Docker Compose |

## 性能参考

| 操作 | 预计耗时（CPU） |
|------|------|
| 图像检测（YOLOv8n） | ~0.5-2s |
| 构图分析 | ~0.2-0.5s |
| 修图（含自动增强） | ~1-3s |
| 全自动构图（一键） | ~3-6s |

> GPU 环境下 YOLO 检测可提速 5-10 倍
