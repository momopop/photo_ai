"""
PhotoAI - AI 服务主入口
提供图像检测、构图分析、自动修图接口
"""

import os
import uuid
import asyncio
from pathlib import Path
from typing import Optional

import aiofiles
from fastapi import FastAPI, File, UploadFile, HTTPException, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse, FileResponse

from modules.detector import ImageDetector
from modules.composition import CompositionAnalyzer
from modules.editor import PhotoEditor
from modules.image_utils import is_image_upload, prepare_image_file

app = FastAPI(
    title="PhotoAI Service",
    description="AI 自动构图与修图后端服务",
    version="1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)
app.mount("/uploads", StaticFiles(directory="uploads"), name="uploads")

# 全局模块实例（懒加载）
_detector: Optional[ImageDetector] = None
_composer: Optional[CompositionAnalyzer] = None
_editor: Optional[PhotoEditor] = None


def get_detector() -> ImageDetector:
    global _detector
    if _detector is None:
        _detector = ImageDetector()
    return _detector


def get_composer() -> CompositionAnalyzer:
    global _composer
    if _composer is None:
        _composer = CompositionAnalyzer()
    return _composer


def get_editor() -> PhotoEditor:
    global _editor
    if _editor is None:
        _editor = PhotoEditor()
    return _editor


def _normalize_crop(crop) -> tuple | None:
    if not crop:
        return None
    if isinstance(crop, dict):
        w = crop.get("width", crop.get("w", 0))
        h = crop.get("height", crop.get("h", 0))
        if w > 0 and h > 0:
            return (int(crop.get("x", 0)), int(crop.get("y", 0)), int(w), int(h))
        return None
    if isinstance(crop, (list, tuple)) and len(crop) == 4:
        return tuple(int(v) for v in crop)
    return None


@app.get("/health")
async def health_check():
    return {"status": "ok", "service": "PhotoAI"}


@app.post("/api/analyze")
async def analyze_image(file: UploadFile = File(...)):
    """
    分析图像：目标检测 + 构图分析
    返回检测到的目标、构图评分、裁剪建议
    """
    if not is_image_upload(file.content_type, file.filename):
        raise HTTPException(status_code=400, detail="只支持图片文件")

    # 保存上传文件
    file_id = str(uuid.uuid4())
    suffix = Path(file.filename).suffix if file.filename else ".jpg"
    if suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}:
        suffix = ".jpg"
    file_path = UPLOAD_DIR / f"{file_id}{suffix}"

    async with aiofiles.open(file_path, "wb") as f:
        content = await file.read()
        await f.write(content)

    try:
        loop = asyncio.get_event_loop()
        ready_path = await loop.run_in_executor(None, prepare_image_file, str(file_path))

        detector = get_detector()
        composer = get_composer()

        # 并行执行检测和构图分析
        detection_result, composition_result = await asyncio.gather(
            loop.run_in_executor(None, detector.detect, ready_path),
            loop.run_in_executor(None, composer.analyze, ready_path)
        )

        return {
            "file_id": file_id,
            "file_url": f"/uploads/{file_id}{suffix}",
            "detection": detection_result,
            "composition": composition_result,
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/edit")
async def edit_image(
    file: UploadFile = File(...),
    brightness: float = Form(0.0),
    contrast: float = Form(0.0),
    saturation: float = Form(0.0),
    sharpness: float = Form(0.0),
    denoise: bool = Form(False),
    auto_enhance: bool = Form(False),
    crop_x: int = Form(0),
    crop_y: int = Form(0),
    crop_w: int = Form(0),
    crop_h: int = Form(0),
):
    """
    修图接口：支持亮度/对比度/饱和度/锐化/降噪/自动增强/裁剪
    """
    if not is_image_upload(file.content_type, file.filename):
        raise HTTPException(status_code=400, detail="只支持图片文件")

    file_id = str(uuid.uuid4())
    suffix = Path(file.filename).suffix if file.filename else ".jpg"
    if suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}:
        suffix = ".jpg"
    input_path = UPLOAD_DIR / f"input_{file_id}{suffix}"
    output_path = UPLOAD_DIR / f"output_{file_id}.jpg"

    async with aiofiles.open(input_path, "wb") as f:
        content = await file.read()
        await f.write(content)

    try:
        editor = get_editor()
        loop = asyncio.get_event_loop()
        ready_path = await loop.run_in_executor(None, prepare_image_file, str(input_path))

        params = {
            "brightness": brightness,
            "contrast": contrast,
            "saturation": saturation,
            "sharpness": sharpness,
            "denoise": denoise,
            "auto_enhance": auto_enhance,
            "crop": (crop_x, crop_y, crop_w, crop_h) if crop_w > 0 and crop_h > 0 else None,
        }

        result = await loop.run_in_executor(
            None, editor.process, ready_path, str(output_path), params
        )

        return {
            "file_id": file_id,
            "output_url": f"/uploads/output_{file_id}.jpg",
            "adjustments_applied": result,
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/auto-compose")
async def auto_compose(file: UploadFile = File(...)):
    """
    全自动构图：检测主体 → 分析构图 → 输出最优裁剪方案 + 修图建议
    """
    if not is_image_upload(file.content_type, file.filename):
        raise HTTPException(status_code=400, detail="只支持图片文件")

    file_id = str(uuid.uuid4())
    suffix = Path(file.filename).suffix if file.filename else ".jpg"
    if suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}:
        suffix = ".jpg"
    file_path = UPLOAD_DIR / f"{file_id}{suffix}"

    async with aiofiles.open(file_path, "wb") as f:
        content = await file.read()
        await f.write(content)

    try:
        detector = get_detector()
        composer = get_composer()
        editor = get_editor()
        loop = asyncio.get_event_loop()
        ready_path = await loop.run_in_executor(None, prepare_image_file, str(file_path))

        # 1. 检测
        detection = await loop.run_in_executor(None, detector.detect, ready_path)
        # 2. 构图分析
        composition = await loop.run_in_executor(None, composer.analyze, ready_path, detection)
        # 3. 自动修图建议
        edit_suggestions = await loop.run_in_executor(None, editor.suggest, ready_path)

        # 4. 生成预览图（应用最优裁剪）
        preview_path = UPLOAD_DIR / f"preview_{file_id}.jpg"
        preview_params = {
            "auto_enhance": True,
            "brightness": edit_suggestions.get("brightness", 0),
            "contrast": edit_suggestions.get("contrast", 0),
            "saturation": edit_suggestions.get("saturation", 0),
            "sharpness": edit_suggestions.get("sharpness", 0),
            "denoise": edit_suggestions.get("denoise", False),
            "crop": _normalize_crop(composition.get("best_crop")),
        }
        await loop.run_in_executor(
            None, editor.process, ready_path, str(preview_path), preview_params
        )

        return {
            "file_id": file_id,
            "original_url": f"/uploads/{file_id}{suffix}",
            "preview_url": f"/uploads/preview_{file_id}.jpg",
            "detection": detection,
            "composition": composition,
            "edit_suggestions": edit_suggestions,
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/download/{filename}")
async def download_file(filename: str):
    file_path = UPLOAD_DIR / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="文件不存在")
    return FileResponse(str(file_path), media_type="image/jpeg")
