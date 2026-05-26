"""
图像检测模块
整合 YOLOv8 目标检测 + MediaPipe 人脸/姿态检测
"""

import cv2
import numpy as np
from pathlib import Path
from typing import Optional


class ImageDetector:
    """
    多模态图像检测器
    - YOLOv8: 通用目标检测（人、动物、物体等）
    - MediaPipe: 人脸关键点 + 人体姿态检测
    """

    def __init__(self):
        self._yolo_model = None
        self._mp_face = None
        self._mp_pose = None
        self._mp_hands = None
        self._initialized = False

    def _lazy_init(self):
        if self._initialized:
            return
        try:
            from ultralytics import YOLO
            model_path = Path("models/yolov8n.pt")
            self._yolo_model = YOLO(str(model_path) if model_path.exists() else "yolov8n.pt")
        except Exception as e:
            print(f"[YOLO] 加载失败，使用 fallback: {e}")
            self._yolo_model = None

        try:
            import mediapipe as mp
            self._mp_face_detection = mp.solutions.face_detection.FaceDetection(
                model_selection=1, min_detection_confidence=0.5
            )
            self._mp_pose_detection = mp.solutions.pose.Pose(
                static_image_mode=True,
                model_complexity=1,
                min_detection_confidence=0.5,
            )
        except Exception as e:
            print(f"[MediaPipe] 加载失败: {e}")
            self._mp_face_detection = None
            self._mp_pose_detection = None

        self._initialized = True

    def detect(self, image_path: str) -> dict:
        """对图像执行全量检测，返回结构化结果"""
        self._lazy_init()

        image = cv2.imread(image_path)
        if image is None:
            return {"error": "无法读取图像", "objects": [], "faces": [], "poses": []}

        h, w = image.shape[:2]
        result = {
            "image_size": {"width": w, "height": h},
            "objects": [],
            "faces": [],
            "poses": [],
            "primary_subject": None,
        }

        # YOLO 目标检测
        yolo_results = self._run_yolo(image, w, h)
        result["objects"] = yolo_results

        # MediaPipe 人脸检测
        face_results = self._run_face_detection(image, w, h)
        result["faces"] = face_results

        # MediaPipe 姿态检测（仅当有人物时）
        has_person = any(obj["label"] == "person" for obj in yolo_results) or len(face_results) > 0
        if has_person:
            pose_results = self._run_pose_detection(image, w, h)
            result["poses"] = pose_results

        # 确定主体
        result["primary_subject"] = self._find_primary_subject(result, w, h)

        return result

    def _run_yolo(self, image: np.ndarray, w: int, h: int) -> list:
        if self._yolo_model is None:
            return self._opencv_fallback_detection(image, w, h)
        try:
            results = self._yolo_model(image, verbose=False, conf=0.4)
            objects = []
            for r in results:
                for box in r.boxes:
                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    conf = float(box.conf[0])
                    cls_id = int(box.cls[0])
                    label = r.names[cls_id]
                    objects.append({
                        "label": label,
                        "confidence": round(conf, 3),
                        "bbox": {
                            "x": round(x1 / w, 4),
                            "y": round(y1 / h, 4),
                            "width": round((x2 - x1) / w, 4),
                            "height": round((y2 - y1) / h, 4),
                        },
                        "bbox_px": {
                            "x": int(x1), "y": int(y1),
                            "w": int(x2 - x1), "h": int(y2 - y1)
                        }
                    })
            return objects
        except Exception as e:
            print(f"[YOLO] 检测失败: {e}")
            return []

    def _opencv_fallback_detection(self, image: np.ndarray, w: int, h: int) -> list:
        """当 YOLO 不可用时，使用 OpenCV 的级联分类器做人脸检测"""
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        faces = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(30, 30))
        results = []
        for (x, y, fw, fh) in faces:
            results.append({
                "label": "person",
                "confidence": 0.8,
                "bbox": {
                    "x": round(x / w, 4), "y": round(y / h, 4),
                    "width": round(fw / w, 4), "height": round(fh / h, 4)
                },
                "bbox_px": {"x": int(x), "y": int(y), "w": int(fw), "h": int(fh)}
            })
        return results

    def _run_face_detection(self, image: np.ndarray, w: int, h: int) -> list:
        if self._mp_face_detection is None:
            return []
        try:
            rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            results = self._mp_face_detection.process(rgb)
            faces = []
            if results.detections:
                for det in results.detections:
                    bb = det.location_data.relative_bounding_box
                    faces.append({
                        "confidence": round(det.score[0], 3),
                        "bbox": {
                            "x": round(bb.xmin, 4),
                            "y": round(bb.ymin, 4),
                            "width": round(bb.width, 4),
                            "height": round(bb.height, 4),
                        },
                        "bbox_px": {
                            "x": int(bb.xmin * w), "y": int(bb.ymin * h),
                            "w": int(bb.width * w), "h": int(bb.height * h)
                        }
                    })
            return faces
        except Exception as e:
            print(f"[MediaPipe Face] 检测失败: {e}")
            return []

    def _run_pose_detection(self, image: np.ndarray, w: int, h: int) -> list:
        if self._mp_pose_detection is None:
            return []
        try:
            rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            results = self._mp_pose_detection.process(rgb)
            poses = []
            if results.pose_landmarks:
                landmarks = []
                for lm in results.pose_landmarks.landmark:
                    landmarks.append({
                        "x": round(lm.x, 4),
                        "y": round(lm.y, 4),
                        "z": round(lm.z, 4),
                        "visibility": round(lm.visibility, 3),
                    })
                # 计算人体边界框
                visible = [(lm["x"], lm["y"]) for lm in landmarks if lm["visibility"] > 0.5]
                if visible:
                    xs = [p[0] for p in visible]
                    ys = [p[1] for p in visible]
                    poses.append({
                        "landmarks": landmarks,
                        "bbox": {
                            "x": round(min(xs), 4), "y": round(min(ys), 4),
                            "width": round(max(xs) - min(xs), 4),
                            "height": round(max(ys) - min(ys), 4),
                        }
                    })
            return poses
        except Exception as e:
            print(f"[MediaPipe Pose] 检测失败: {e}")
            return []

    def _find_primary_subject(self, result: dict, w: int, h: int) -> Optional[dict]:
        """找到图像中最重要的主体（面积最大且置信度最高）"""
        candidates = []

        # 优先人脸
        for face in result["faces"]:
            area = face["bbox"]["width"] * face["bbox"]["height"]
            candidates.append({"type": "face", "priority": 3, "area": area, "bbox": face["bbox"]})

        # 其次是人物
        for obj in result["objects"]:
            if obj["label"] == "person":
                area = obj["bbox"]["width"] * obj["bbox"]["height"]
                candidates.append({"type": "person", "priority": 2, "area": area, "bbox": obj["bbox"]})

        # 其他显著物体
        for obj in result["objects"]:
            if obj["label"] != "person" and obj["confidence"] > 0.6:
                area = obj["bbox"]["width"] * obj["bbox"]["height"]
                candidates.append({"type": obj["label"], "priority": 1, "area": area, "bbox": obj["bbox"]})

        if not candidates:
            return None

        candidates.sort(key=lambda x: (x["priority"], x["area"]), reverse=True)
        return candidates[0]
