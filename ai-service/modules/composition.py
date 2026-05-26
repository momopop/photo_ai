"""
构图分析模块
实现三分法、黄金比例、对称性、引导线、前景背景等构图规则分析
并基于主体位置生成最优裁剪建议
"""

import cv2
import numpy as np
from typing import Optional


class CompositionAnalyzer:
    """
    构图评分与建议系统
    综合多种构图规则计算总分并给出优化建议
    """

    # 黄金比例
    PHI = 1.61803398875
    # 三分法线位置（归一化）
    THIRDS = [1 / 3, 2 / 3]

    def analyze(self, image_path: str, detection_result: Optional[dict] = None) -> dict:
        """
        对图像进行全面构图分析
        :param image_path: 图像路径
        :param detection_result: 可选，来自 detector 的检测结果
        :return: 构图分析结果字典
        """
        image = cv2.imread(image_path)
        if image is None:
            return {"error": "无法读取图像"}

        h, w = image.shape[:2]
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

        scores = {}
        suggestions = []
        crop_proposals = []

        # 各维度评分
        scores["thirds"] = self._rule_of_thirds(gray, w, h, detection_result)
        scores["symmetry"] = self._symmetry_score(gray, w, h)
        scores["balance"] = self._visual_balance(gray, w, h)
        scores["depth"] = self._depth_of_field_score(gray, w, h)
        scores["horizon"] = self._horizon_line(gray, w, h)
        scores["leading_lines"] = self._leading_lines(gray, w, h)
        scores["subject_placement"] = self._subject_placement(detection_result, w, h)

        # 加权总分
        weights = {
            "thirds": 0.25,
            "symmetry": 0.10,
            "balance": 0.20,
            "depth": 0.10,
            "horizon": 0.10,
            "leading_lines": 0.10,
            "subject_placement": 0.15,
        }
        total_score = sum(scores[k] * weights[k] for k in weights)
        total_score = round(total_score, 1)

        # 生成文字建议
        if scores["thirds"] < 50:
            suggestions.append("将主体移至三分法交叉点可提升视觉冲击力")
        if scores["balance"] < 50:
            suggestions.append("画面视觉重心偏移，可适当调整主体位置或增加前景元素")
        if scores["horizon"] < 50:
            suggestions.append("水平线倾斜，建议调整为水平或符合三分法位置")
        if scores["subject_placement"] < 50:
            suggestions.append("主体过于居中，留白可增加动感和呼吸感")
        if scores["depth"] < 40:
            suggestions.append("画面层次感不足，可尝试增加前景元素")
        if total_score >= 75:
            suggestions.append("构图优秀！")
        elif total_score >= 55:
            suggestions.append("构图良好，小幅调整可更出彩")

        # 生成裁剪建议
        crop_proposals = self._generate_crop_proposals(image, w, h, detection_result)

        # 最优构图比例建议
        aspect_ratios = self._suggest_aspect_ratios(w, h, detection_result)

        return {
            "total_score": total_score,
            "scores": {k: round(v, 1) for k, v in scores.items()},
            "suggestions": suggestions,
            "crop_proposals": crop_proposals,
            "best_crop": crop_proposals[0] if crop_proposals else None,
            "aspect_ratio_suggestions": aspect_ratios,
            "composition_type": self._classify_composition(scores),
        }

    def _rule_of_thirds(self, gray: np.ndarray, w: int, h: int, detection=None) -> float:
        """三分法评分：主体是否落在三分点附近"""
        if detection and detection.get("primary_subject"):
            subj = detection["primary_subject"]["bbox"]
            cx = subj["x"] + subj["width"] / 2
            cy = subj["y"] + subj["height"] / 2
            # 计算到最近三分点的距离
            third_points = [
                (1 / 3, 1 / 3), (2 / 3, 1 / 3),
                (1 / 3, 2 / 3), (2 / 3, 2 / 3),
            ]
            min_dist = min(((cx - px) ** 2 + (cy - py) ** 2) ** 0.5 for px, py in third_points)
            score = max(0, 100 - min_dist * 300)
            return score

        # 无检测结果时分析显著性图（需 opencv-contrib；不可用时回退默认分）
        try:
            if not hasattr(cv2, "saliency"):
                return 50.0
            saliency = cv2.saliency.StaticSaliencyFineGrained_create()
            ok, saliency_map = saliency.computeSaliency(gray)
            if not ok or saliency_map is None:
                return 50.0
            saliency_map = (saliency_map * 255).astype("uint8")

            third_h, third_w = h // 3, w // 3
            third_region = saliency_map[third_h:2 * third_h, third_w:2 * third_w]
            center_region = saliency_map[h // 4:3 * h // 4, w // 4:3 * w // 4]

            score = min(100, float(np.mean(third_region)) / float(np.mean(center_region) + 1e-6) * 50)
            return score
        except Exception:
            return 50.0

    def _symmetry_score(self, gray: np.ndarray, w: int, h: int) -> float:
        """对称性评分"""
        left = gray[:, :w // 2]
        right = cv2.flip(gray[:, w // 2:], 1)
        if left.shape != right.shape:
            right = right[:, :left.shape[1]]
        diff = np.abs(left.astype(float) - right.astype(float))
        score = max(0, 100 - float(np.mean(diff)) * 2)
        return score

    def _visual_balance(self, gray: np.ndarray, w: int, h: int) -> float:
        """视觉重心平衡评分"""
        # 计算亮度重心
        total = float(np.sum(gray)) + 1e-6
        y_coords, x_coords = np.mgrid[0:h, 0:w]
        cx = float(np.sum(x_coords * gray)) / total / w
        cy = float(np.sum(y_coords * gray)) / total / h
        # 重心偏离中心的距离
        dist = ((cx - 0.5) ** 2 + (cy - 0.5) ** 2) ** 0.5
        score = max(0, 100 - dist * 200)
        return score

    def _depth_of_field_score(self, gray: np.ndarray, w: int, h: int) -> float:
        """景深/层次感评分：基于不同区域的清晰度差异"""
        top = cv2.Laplacian(gray[:h // 2, :], cv2.CV_64F).var()
        bottom = cv2.Laplacian(gray[h // 2:, :], cv2.CV_64F).var()
        # 前清后虚 或 虚实有别 -> 有层次感
        ratio = min(top, bottom) / (max(top, bottom) + 1e-6)
        score = max(0, min(100, (1 - ratio) * 100))
        return score

    def _horizon_line(self, gray: np.ndarray, w: int, h: int) -> float:
        """水平线评分：检测主要水平线是否在三分法位置"""
        edges = cv2.Canny(gray, 50, 150)
        lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=80, minLineLength=w // 4, maxLineGap=20)
        if lines is None:
            return 60.0

        horizontal_lines = []
        for line in lines:
            x1, y1, x2, y2 = line[0]
            angle = abs(np.degrees(np.arctan2(y2 - y1, x2 - x1)))
            if angle < 10 or angle > 170:
                y_pos = (y1 + y2) / 2 / h
                horizontal_lines.append(y_pos)

        if not horizontal_lines:
            return 60.0

        main_horizon = np.median(horizontal_lines)
        # 距离三分法线的距离
        dist = min(abs(main_horizon - 1 / 3), abs(main_horizon - 2 / 3))
        score = max(0, 100 - dist * 300)
        return score

    def _leading_lines(self, gray: np.ndarray, w: int, h: int) -> float:
        """引导线评分：检测是否有从边缘指向主体的线条"""
        edges = cv2.Canny(gray, 30, 100)
        lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=50, minLineLength=w // 6, maxLineGap=30)
        if lines is None:
            return 40.0

        # 汇聚线条数量
        angles = []
        for line in lines:
            x1, y1, x2, y2 = line[0]
            angle = np.degrees(np.arctan2(y2 - y1, x2 - x1)) % 180
            angles.append(angle)

        if len(angles) < 2:
            return 40.0

        # 线条多样性 -> 引导线存在
        angle_std = np.std(angles)
        score = min(100, angle_std * 1.5)
        return score

    def _subject_placement(self, detection, w: int, h: int) -> float:
        """主体放置评分：评估主体与画面的关系"""
        if not detection or not detection.get("primary_subject"):
            return 50.0

        subj = detection["primary_subject"]["bbox"]
        cx = subj["x"] + subj["width"] / 2
        cy = subj["y"] + subj["height"] / 2
        area = subj["width"] * subj["height"]

        # 主体不要太靠边
        edge_penalty = 0
        if cx < 0.1 or cx > 0.9:
            edge_penalty += 20
        if cy < 0.1 or cy > 0.9:
            edge_penalty += 20

        # 主体面积合适（5%-60% 之间最佳）
        area_score = 100 if 0.05 <= area <= 0.6 else max(0, 100 - abs(area - 0.3) * 100)

        # 不要完全居中（留白美学）
        center_dist = ((cx - 0.5) ** 2 + (cy - 0.5) ** 2) ** 0.5
        offset_score = min(100, center_dist * 250)

        score = (area_score * 0.4 + offset_score * 0.6) - edge_penalty
        return max(0, score)

    def _generate_crop_proposals(self, image, w, h, detection=None) -> list:
        """生成多种裁剪方案"""
        proposals = []

        # 目标主体的裁剪
        if detection and detection.get("primary_subject"):
            subj = detection["primary_subject"]["bbox"]
            subject_crop = self._crop_around_subject(subj, w, h)
            subject_crop["name"] = "主体聚焦"
            subject_crop["description"] = "裁剪聚焦于主体，去除干扰背景"
            proposals.append(subject_crop)

        # 三分法优化裁剪
        thirds_crop = self._thirds_optimized_crop(image, w, h, detection)
        thirds_crop["name"] = "三分法构图"
        thirds_crop["description"] = "将主体置于三分法交叉点"
        proposals.append(thirds_crop)

        # 黄金比例裁剪
        golden_crop = self._golden_ratio_crop(w, h)
        golden_crop["name"] = "黄金比例"
        golden_crop["description"] = "符合黄金分割比的经典构图"
        proposals.append(golden_crop)

        # 1:1 方形裁剪（社交媒体常用）
        square_crop = self._square_crop(w, h, detection)
        square_crop["name"] = "正方形"
        square_crop["description"] = "1:1 方形构图，适合社交媒体"
        proposals.append(square_crop)

        # 16:9 宽屏裁剪
        wide_crop = self._widescreen_crop(w, h, detection)
        wide_crop["name"] = "宽屏 16:9"
        wide_crop["description"] = "电影感宽屏构图"
        proposals.append(wide_crop)

        return proposals

    def _crop_around_subject(self, subj: dict, w: int, h: int, padding=0.15) -> dict:
        """以主体为中心，带 padding 的裁剪"""
        cx = (subj["x"] + subj["width"] / 2)
        cy = (subj["y"] + subj["height"] / 2)
        half_w = subj["width"] / 2 + padding
        half_h = subj["height"] / 2 + padding

        x1 = max(0, cx - half_w)
        y1 = max(0, cy - half_h)
        x2 = min(1, cx + half_w)
        y2 = min(1, cy + half_h)

        return {
            "x": round(x1 * w), "y": round(y1 * h),
            "width": round((x2 - x1) * w), "height": round((y2 - y1) * h),
            "ratio": f"{round((x2-x1)*w)}:{round((y2-y1)*h)}",
        }

    def _thirds_optimized_crop(self, image, w, h, detection) -> dict:
        """三分法最优裁剪"""
        if detection and detection.get("primary_subject"):
            subj = detection["primary_subject"]["bbox"]
            # 将主体中心移到最近三分点
            cx = subj["x"] + subj["width"] / 2
            cy = subj["y"] + subj["height"] / 2
            target_x = 1 / 3 if cx < 0.5 else 2 / 3
            target_y = 1 / 3 if cy < 0.5 else 2 / 3
            offset_x = target_x - cx
            offset_y = target_y - cy
            # 平移裁剪框
            crop_w = min(w, int(w * 0.85))
            crop_h = min(h, int(h * 0.85))
            x = max(0, int((0.5 - offset_x) * w - crop_w // 2))
            y = max(0, int((0.5 - offset_y) * h - crop_h // 2))
            x = min(x, w - crop_w)
            y = min(y, h - crop_h)
            return {"x": x, "y": y, "width": crop_w, "height": crop_h}
        return {"x": int(w * 0.075), "y": int(h * 0.075),
                "width": int(w * 0.85), "height": int(h * 0.85)}

    def _golden_ratio_crop(self, w, h) -> dict:
        """黄金比例裁剪"""
        if w / h > self.PHI:
            nw = int(h * self.PHI)
            return {"x": (w - nw) // 2, "y": 0, "width": nw, "height": h}
        else:
            nh = int(w / self.PHI)
            return {"x": 0, "y": (h - nh) // 2, "width": w, "height": nh}

    def _square_crop(self, w, h, detection) -> dict:
        """1:1 方形裁剪"""
        side = min(w, h)
        if detection and detection.get("primary_subject"):
            subj = detection["primary_subject"]["bbox"]
            cx = int((subj["x"] + subj["width"] / 2) * w)
            cy = int((subj["y"] + subj["height"] / 2) * h)
        else:
            cx, cy = w // 2, h // 2
        x = max(0, min(cx - side // 2, w - side))
        y = max(0, min(cy - side // 2, h - side))
        return {"x": x, "y": y, "width": side, "height": side}

    def _widescreen_crop(self, w, h, detection) -> dict:
        """16:9 宽屏裁剪"""
        target_h = int(w * 9 / 16)
        if target_h > h:
            target_w = int(h * 16 / 9)
            x = (w - target_w) // 2
            if detection and detection.get("primary_subject"):
                subj = detection["primary_subject"]["bbox"]
                cx = int((subj["x"] + subj["width"] / 2) * w)
                x = max(0, min(cx - target_w // 2, w - target_w))
            return {"x": x, "y": 0, "width": target_w, "height": h}
        y = (h - target_h) // 2
        if detection and detection.get("primary_subject"):
            subj = detection["primary_subject"]["bbox"]
            cy = int((subj["y"] + subj["height"] / 2) * h)
            y = max(0, min(cy - target_h // 2, h - target_h))
        return {"x": 0, "y": y, "width": w, "height": target_h}

    def _suggest_aspect_ratios(self, w, h, detection) -> list:
        """建议适合的输出比例"""
        current_ratio = w / h
        ratios = [
            {"name": "原图", "ratio": f"{w}:{h}", "value": current_ratio},
            {"name": "1:1", "ratio": "1:1", "value": 1.0},
            {"name": "4:3", "ratio": "4:3", "value": 4 / 3},
            {"name": "16:9", "ratio": "16:9", "value": 16 / 9},
            {"name": "3:4 竖版", "ratio": "3:4", "value": 3 / 4},
            {"name": "9:16 竖屏", "ratio": "9:16", "value": 9 / 16},
        ]
        # 按与当前比例的差异排序，但人像优先竖版
        has_person = detection and detection.get("primary_subject") and \
                     detection["primary_subject"]["type"] in ["face", "person"]
        if has_person and h > w:
            ratios.sort(key=lambda r: (0 if r["value"] < 1 else 1, abs(r["value"] - current_ratio)))
        else:
            ratios.sort(key=lambda r: abs(r["value"] - current_ratio))
        return ratios[:4]

    def _classify_composition(self, scores: dict) -> str:
        """根据评分特征分类构图类型"""
        if scores["symmetry"] > 70:
            return "对称构图"
        if scores["leading_lines"] > 70:
            return "引导线构图"
        if scores["thirds"] > 70:
            return "三分法构图"
        if scores["depth"] > 70:
            return "景深层次构图"
        if scores["balance"] > 70:
            return "平衡构图"
        return "自由构图"
