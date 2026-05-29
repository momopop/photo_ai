"""
修图模块
基于 OpenCV + PIL 实现专业级修图功能
包括：色彩增强、锐化、降噪、自动白平衡、HDR 效果、裁剪等
"""

import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
from typing import Optional, Tuple


class PhotoEditor:
    """
    专业修图引擎
    参数范围均为 [-100, 100]（0 代表不变），自动增强模式会分析图像并给出建议值
    """

    @staticmethod
    def _normalize_crop(crop) -> Optional[Tuple[int, int, int, int]]:
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

    def process(self, input_path: str, output_path: str, params: dict) -> dict:
        """
        执行修图流程
        :param input_path: 输入图像路径
        :param output_path: 输出图像路径
        :param params: 修图参数字典
        :return: 实际执行的操作摘要
        """
        image = cv2.imread(input_path)
        if image is None:
            raise ValueError(f"无法读取图像: {input_path}")

        applied = {}

        # 1. 裁剪（最先执行）
        crop = self._normalize_crop(params.get("crop"))
        if crop:
            x, y, cw, ch = crop
            h, w = image.shape[:2]
            x = max(0, min(x, w - 1))
            y = max(0, min(y, h - 1))
            cw = min(cw, w - x)
            ch = min(ch, h - y)
            if cw > 10 and ch > 10:
                image = image[y:y + ch, x:x + cw]
                applied["crop"] = {"x": x, "y": y, "w": cw, "h": ch}

        # 2. 自动增强（分析后应用最优参数）
        if params.get("auto_enhance"):
            image, auto_params = self._auto_enhance(image)
            applied["auto_enhance"] = auto_params

        # 3. 转为 PIL 进行色彩调整
        pil_image = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))

        # 4. 亮度
        brightness = params.get("brightness", 0)
        if abs(brightness) > 1:
            factor = 1.0 + brightness / 100.0
            pil_image = ImageEnhance.Brightness(pil_image).enhance(max(0.1, factor))
            applied["brightness"] = brightness

        # 5. 对比度
        contrast = params.get("contrast", 0)
        if abs(contrast) > 1:
            factor = 1.0 + contrast / 100.0
            pil_image = ImageEnhance.Contrast(pil_image).enhance(max(0.1, factor))
            applied["contrast"] = contrast

        # 6. 饱和度
        saturation = params.get("saturation", 0)
        if abs(saturation) > 1:
            factor = 1.0 + saturation / 100.0
            pil_image = ImageEnhance.Color(pil_image).enhance(max(0.0, factor))
            applied["saturation"] = saturation

        # 7. 锐化
        sharpness = params.get("sharpness", 0)
        if sharpness > 1:
            factor = 1.0 + sharpness / 50.0
            pil_image = ImageEnhance.Sharpness(pil_image).enhance(factor)
            applied["sharpness"] = sharpness
        elif sharpness < -1:
            # 负值 = 模糊（柔化）
            radius = abs(sharpness) / 20.0
            pil_image = pil_image.filter(ImageFilter.GaussianBlur(radius=radius))
            applied["blur"] = abs(sharpness)

        # 转回 OpenCV
        image = cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)

        # 8. 降噪
        if params.get("denoise"):
            image = cv2.fastNlMeansDenoisingColored(image, None, 10, 10, 7, 21)
            applied["denoise"] = True

        # 保存输出
        cv2.imwrite(output_path, image, [cv2.IMWRITE_JPEG_QUALITY, 95])
        return applied

    def refine(self, input_path: str, output_path: str, target_megapixels: float = 8.0) -> dict:
        """
        AI 精修：还原/超分辨率 + HDR 高画质处理。
        适合对本地优化图（已缩至 1920px）进行画质提升，输出接近原图像素量。

        流程：
          1. 读取输入（本地优化图，约 1920px）
          2. 计算目标尺寸（长边提升至 target_megapixels 对应像素，双三次插值）
          3. 自动白平衡（还原真实色彩）
          4. CLAHE 自适应对比度增强（局部 HDR 效果）
          5. 轻量 Unsharp Mask（仅轻微锐化，避免过度）
          6. 高品质 JPEG 保存
        """
        image = cv2.imread(input_path)
        if image is None:
            raise ValueError(f"无法读取图像: {input_path}")

        applied = {}
        h, w = image.shape[:2]
        current_mp = (w * h) / 1_000_000

        # ── Step 1: 超分辨率（双三次插值放大至目标像素量） ──────────────────
        if current_mp < target_megapixels * 0.9:
            scale = (target_megapixels / max(current_mp, 0.01)) ** 0.5
            new_w = int(w * scale)
            new_h = int(h * scale)
            image = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_CUBIC)
            applied["upscale"] = {"from": f"{w}x{h}", "to": f"{new_w}x{new_h}"}
            h, w = new_h, new_w

        # ── Step 2: 自动白平衡 ────────────────────────────────────────────────
        image = self._gray_world_white_balance(image)
        applied["white_balance"] = True

        # ── Step 3: HDR — CLAHE 自适应对比度（局部细节增强） ─────────────────
        lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        # clipLimit=2.5：比自动增强（2.0）略强，增强高光/阴影细节
        clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))
        l = clahe.apply(l)
        image = cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_LAB2BGR)
        applied["hdr_clahe"] = True

        # ── Step 4: 饱和度轻微提升（+8%，使颜色更鲜活但不失真） ──────────────
        pil = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        pil = ImageEnhance.Color(pil).enhance(1.08)
        image = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
        applied["saturation_boost"] = 8

        # ── Step 5: 轻量 Unsharp Mask（仅微量，避免过度锐化） ────────────────
        blurred = cv2.GaussianBlur(image, (0, 0), sigmaX=1.0)
        # strength=0.2：远低于强锐化的 0.5+，仅恢复插值模糊
        image = cv2.addWeighted(image, 1.2, blurred, -0.2, 0)
        image = np.clip(image, 0, 255).astype(np.uint8)
        applied["unsharp_mask"] = {"sigma": 1.0, "strength": 0.2}

        # ── Step 6: 高品质输出 ────────────────────────────────────────────────
        cv2.imwrite(output_path, image, [cv2.IMWRITE_JPEG_QUALITY, 97])
        return applied

    def suggest(self, image_path: str) -> dict:
        """
        分析图像并给出修图建议参数
        :return: 推荐参数字典
        """
        image = cv2.imread(image_path)
        if image is None:
            return {}

        suggestions = {}

        # 分析亮度
        brightness_val = self._analyze_brightness(image)
        if brightness_val < 80:
            suggestions["brightness"] = min(40, int((80 - brightness_val) * 0.5))
        elif brightness_val > 190:
            suggestions["brightness"] = max(-40, -int((brightness_val - 190) * 0.5))
        else:
            suggestions["brightness"] = 0

        # 分析对比度
        contrast_val = self._analyze_contrast(image)
        if contrast_val < 40:
            suggestions["contrast"] = min(30, int((40 - contrast_val) * 0.8))
        else:
            suggestions["contrast"] = 0

        # 分析饱和度
        saturation_val = self._analyze_saturation(image)
        if saturation_val < 60:
            suggestions["saturation"] = min(25, int((60 - saturation_val) * 0.5))
        elif saturation_val > 180:
            suggestions["saturation"] = max(-20, -int((saturation_val - 180) * 0.3))
        else:
            suggestions["saturation"] = 0

        # 锐化建议（基于清晰度）
        sharpness_val = self._analyze_sharpness(image)
        if sharpness_val < 100:
            suggestions["sharpness"] = 20
        else:
            suggestions["sharpness"] = 0

        # 降噪建议
        noise_val = self._analyze_noise(image)
        suggestions["denoise"] = noise_val > 15

        suggestions["auto_enhance"] = True
        return suggestions

    def _auto_enhance(self, image: np.ndarray) -> Tuple[np.ndarray, dict]:
        """自动增强：自动白平衡 + CLAHE 自适应对比度增强"""
        params_applied = {}

        # 自动白平衡（灰世界算法）
        wb_image = self._gray_world_white_balance(image)
        params_applied["white_balance"] = "gray_world"

        # CLAHE 自适应直方图均衡化（只作用于亮度通道）
        lab = cv2.cvtColor(wb_image, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        l_clahe = clahe.apply(l)
        lab_clahe = cv2.merge([l_clahe, a, b])
        enhanced = cv2.cvtColor(lab_clahe, cv2.COLOR_LAB2BGR)
        params_applied["clahe"] = True

        # 轻微去雾（暗角修复）
        enhanced = self._dehaze_light(enhanced)
        params_applied["dehaze"] = True

        return enhanced, params_applied

    def _gray_world_white_balance(self, image: np.ndarray) -> np.ndarray:
        """灰世界白平衡算法"""
        result = image.astype(np.float32)
        avg_b = np.mean(result[:, :, 0])
        avg_g = np.mean(result[:, :, 1])
        avg_r = np.mean(result[:, :, 2])
        avg = (avg_b + avg_g + avg_r) / 3
        result[:, :, 0] = np.clip(result[:, :, 0] * (avg / (avg_b + 1e-6)), 0, 255)
        result[:, :, 1] = np.clip(result[:, :, 1] * (avg / (avg_g + 1e-6)), 0, 255)
        result[:, :, 2] = np.clip(result[:, :, 2] * (avg / (avg_r + 1e-6)), 0, 255)
        return result.astype(np.uint8)

    def _dehaze_light(self, image: np.ndarray) -> np.ndarray:
        """轻度去雾/提升通透感"""
        # 使用暗通道先验的简化版本
        kernel = np.ones((15, 15), np.float32) / 225
        dark_channel = np.min(image, axis=2)
        dark_blurred = cv2.filter2D(dark_channel.astype(np.float32), -1, kernel)
        atmosphere = np.percentile(dark_blurred, 99)
        t = 1 - 0.6 * (dark_blurred / (atmosphere + 1e-6))
        t = np.clip(t, 0.1, 1.0)
        result = np.zeros_like(image, dtype=np.float32)
        for c in range(3):
            result[:, :, c] = ((image[:, :, c].astype(np.float32) - atmosphere) / t + atmosphere)
        return np.clip(result, 0, 255).astype(np.uint8)

    def _analyze_brightness(self, image: np.ndarray) -> float:
        """分析图像平均亮度"""
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        return float(np.mean(gray))

    def _analyze_contrast(self, image: np.ndarray) -> float:
        """分析图像对比度（标准差）"""
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        return float(np.std(gray))

    def _analyze_saturation(self, image: np.ndarray) -> float:
        """分析图像平均饱和度"""
        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
        return float(np.mean(hsv[:, :, 1]))

    def _analyze_sharpness(self, image: np.ndarray) -> float:
        """分析图像清晰度（Laplacian方差）"""
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        return float(cv2.Laplacian(gray, cv2.CV_64F).var())

    def _analyze_noise(self, image: np.ndarray) -> float:
        """估算图像噪点水平"""
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY).astype(np.float64)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        noise = np.std(gray - blurred)
        return float(noise)
