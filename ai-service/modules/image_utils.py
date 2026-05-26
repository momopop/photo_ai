"""图片上传校验与格式转换"""

from pathlib import Path

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}


def is_image_upload(content_type: str | None, filename: str | None) -> bool:
    if content_type and content_type.startswith("image/"):
        return True
    if filename:
        return Path(filename).suffix.lower() in IMAGE_EXTENSIONS
    return False


def prepare_image_file(file_path: str) -> str:
    """
    确保 OpenCV 能读取图片；HEIC 等格式转为 JPG。
    返回可用于处理的文件路径。
    """
    import cv2
    from PIL import Image

    path = Path(file_path)
    if cv2.imread(str(path)) is not None:
        return str(path)

    try:
        with Image.open(path) as img:
            out = path.with_suffix(".jpg")
            img.convert("RGB").save(out, "JPEG", quality=95)
        return str(out)
    except Exception as exc:
        raise ValueError("无法读取图片，请使用 JPG/PNG 格式") from exc
