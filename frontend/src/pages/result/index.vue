<template>
  <view class="result-page">
    <scroll-view class="result-scroll" scroll-y>
      <!-- 图片预览区 -->
      <view class="image-section">
        <view class="image-container" @tap="toggleOverlay">
          <image
            class="main-image"
            :src="currentImagePath"
            mode="aspectFit"
            @load="onImageLoad"
          />
          <!-- 构图辅助线覆盖层 -->
          <canvas
            v-if="showOverlay && imageLoaded"
            canvas-id="composition-canvas"
            class="composition-canvas"
            :style="{ width: canvasWidth + 'px', height: canvasHeight + 'px' }"
          />
        </view>
        <text class="tap-hint">点击图片切换构图辅助线</text>
      </view>

      <!-- 构图评分卡 -->
      <view class="score-section" v-if="composition">
        <view class="score-header">
          <view class="score-main">
            <view class="score-ring">
              <text class="score-num">{{ composition.total_score }}</text>
              <text class="score-unit">分</text>
            </view>
            <view class="score-info">
              <text class="score-label">构图综合评分</text>
              <text class="score-type">{{ composition.composition_type }}</text>
              <view class="score-badge" :class="scoreLevel.cls">
                <text class="score-badge-text">{{ scoreLevel.label }}</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 各维度评分 -->
        <view class="dimensions">
          <view class="dim-item" v-for="dim in dimensionScores" :key="dim.key">
            <view class="dim-header">
              <text class="dim-name">{{ dim.name }}</text>
              <text class="dim-value">{{ dim.value }}</text>
            </view>
            <view class="dim-bar">
              <view class="dim-fill" :style="{ width: dim.value + '%', background: dim.color }"></view>
            </view>
          </view>
        </view>
      </view>

      <!-- AI 建议 -->
      <view class="suggestions-section" v-if="composition && composition.suggestions.length">
        <text class="section-title">AI 构图建议</text>
        <view class="suggestion-list">
          <view class="suggestion-item" v-for="(s, idx) in composition.suggestions" :key="idx">
            <text class="s-icon">{{ idx === 0 ? '💡' : '→' }}</text>
            <text class="s-text">{{ s }}</text>
          </view>
        </view>
      </view>

      <!-- 检测到的对象 -->
      <view class="detection-section" v-if="detection && detection.objects.length > 0">
        <text class="section-title">检测到的主体</text>
        <view class="detection-tags">
          <view class="det-tag" v-for="obj in topObjects" :key="obj.label + obj.confidence">
            <text class="det-icon">🎯</text>
            <text class="det-label">{{ obj.label }}</text>
            <text class="det-conf">{{ Math.round(obj.confidence * 100) }}%</text>
          </view>
          <view v-if="detection.faces.length > 0" class="det-tag face-tag">
            <text class="det-icon">👤</text>
            <text class="det-label">人脸</text>
            <text class="det-conf">{{ detection.faces.length }} 个</text>
          </view>
        </view>
      </view>

      <!-- 裁剪方案 -->
      <view class="crops-section" v-if="composition && composition.crop_proposals">
        <text class="section-title">推荐构图方案</text>
        <scroll-view scroll-x class="crops-scroll">
          <view class="crops-list">
            <view
              class="crop-card"
              v-for="(crop, idx) in composition.crop_proposals"
              :key="idx"
              :class="{ 'crop-selected': selectedCropIdx === idx }"
              @tap="selectCrop(idx, crop)"
            >
              <view class="crop-preview">
                <image class="crop-thumb" :src="currentImagePath" mode="aspectFill" />
                <view class="crop-overlay-info">
                  <text class="crop-ratio">{{ getCropRatioText(crop) }}</text>
                </view>
              </view>
              <text class="crop-name">{{ crop.name }}</text>
              <text class="crop-desc">{{ crop.desc || crop.description }}</text>
            </view>
          </view>
        </scroll-view>
      </view>

      <!-- 操作按钮 -->
      <view class="actions-section">
        <view class="btn-row">
          <view class="btn-auto" @tap="handleAutoCompose" :class="{ loading: autoLoading }">
            <text class="btn-icon">⚡</text>
            <text class="btn-text">{{ autoLoading ? 'AI 处理中...' : '一键 AI 优化' }}</text>
          </view>
        </view>
        <view class="btn-row secondary-row">
          <view class="btn-edit" @tap="goToEditor">
            <text class="btn-icon">🎨</text>
            <text class="btn-text">手动修图</text>
          </view>
          <view class="btn-share" @tap="shareImage" v-if="autoResult">
            <text class="btn-icon">💾</text>
            <text class="btn-text">保存</text>
          </view>
        </view>
      </view>

      <!-- 自动优化结果预览 -->
      <view v-if="autoResult" class="preview-compare">
        <text class="section-title">AI 优化效果</text>
        <view class="compare-wrap">
          <view class="compare-item">
            <image class="compare-img" :src="currentImagePath" mode="aspectFit" />
            <text class="compare-label">原图</text>
          </view>
          <text class="compare-arrow">→</text>
          <view class="compare-item">
            <image class="compare-img" :src="autoResult.previewUrl" mode="aspectFit" />
            <text class="compare-label">AI 优化</text>
          </view>
        </view>
        <view v-if="autoResult.editSuggestions" class="applied-tags">
          <text class="applied-title">已应用优化：</text>
          <view class="tags-row">
            <text class="applied-tag" v-if="autoResult.editSuggestions.brightness != 0">
              亮度 {{ autoResult.editSuggestions.brightness > 0 ? '+' : '' }}{{ autoResult.editSuggestions.brightness }}
            </text>
            <text class="applied-tag" v-if="autoResult.editSuggestions.contrast != 0">
              对比度 {{ autoResult.editSuggestions.contrast > 0 ? '+' : '' }}{{ autoResult.editSuggestions.contrast }}
            </text>
            <text class="applied-tag" v-if="autoResult.editSuggestions.saturation != 0">
              饱和度 {{ autoResult.editSuggestions.saturation > 0 ? '+' : '' }}{{ autoResult.editSuggestions.saturation }}
            </text>
            <text class="applied-tag" v-if="autoResult.editSuggestions.denoise">降噪</text>
            <text class="applied-tag" v-if="autoResult.editSuggestions.auto_enhance">自动增强</text>
          </view>
        </view>
      </view>

      <view style="height: 120rpx;"></view>
    </scroll-view>

    <!-- 全屏加载 -->
    <view v-if="autoLoading" class="loading-overlay">
      <view class="loading-box">
        <view class="ai-loading-icon">🤖</view>
        <text class="loading-title">AI 智能优化中</text>
        <text class="loading-sub">正在分析构图 · 自动调色 · 智能裁剪</text>
        <view class="loading-progress">
          <view class="progress-bar" :style="{ width: loadingProgress + '%' }"></view>
        </view>
        <text class="progress-text">{{ loadingProgress }}%</text>
      </view>
    </view>
  </view>
</template>

<script>
import { ref, computed, onMounted, nextTick } from 'vue';
import { usePhotoStore } from '../../store/photo';
import { photoAPI } from '../../api/photoai';

export default {
  name: 'ResultPage',
  setup() {
    const photoStore = usePhotoStore();
    const currentImagePath = computed(() => photoStore.currentImagePath);
    const detection = computed(() => photoStore.analysisResult.detection);
    const composition = computed(() => photoStore.analysisResult.composition);

    const showOverlay = ref(false);
    const imageLoaded = ref(false);
    const canvasWidth = ref(375);
    const canvasHeight = ref(280);
    const selectedCropIdx = ref(0);
    const autoLoading = ref(false);
    const autoResult = ref(null);
    const loadingProgress = ref(0);

    const scoreLevel = computed(() => {
      const s = composition.value?.total_score || 0;
      if (s >= 80) return { label: '优秀', cls: 'badge-gold' };
      if (s >= 65) return { label: '良好', cls: 'badge-blue' };
      if (s >= 50) return { label: '一般', cls: 'badge-gray' };
      return { label: '待优化', cls: 'badge-red' };
    });

    const dimensionScores = computed(() => {
      const scores = composition.value?.scores || {};
      const colors = {
        thirds: '#7c6af5',
        symmetry: '#a855f7',
        balance: '#ec4899',
        depth: '#06b6d4',
        horizon: '#10b981',
        leading_lines: '#f59e0b',
        subject_placement: '#ef4444',
      };
      const names = {
        thirds: '三分法',
        symmetry: '对称性',
        balance: '视觉平衡',
        depth: '层次感',
        horizon: '水平线',
        leading_lines: '引导线',
        subject_placement: '主体布置',
      };
      return Object.keys(scores).map((k) => ({
        key: k,
        name: names[k] || k,
        value: Math.round(scores[k]),
        color: colors[k] || '#7c6af5',
      }));
    });

    const topObjects = computed(() => {
      return (detection.value?.objects || []).slice(0, 5);
    });

    const getCropRatioText = (crop) => {
      if (!crop) return '';
      const gcd = (a, b) => b === 0 ? a : gcd(b, a % b);
      const w = crop.width, h = crop.height;
      const d = gcd(w, h);
      return `${Math.round(w / d)}:${Math.round(h / d)}`;
    };

    const toggleOverlay = () => {
      showOverlay.value = !showOverlay.value;
      if (showOverlay.value && imageLoaded.value) {
        nextTick(() => drawCompositionLines());
      }
    };

    const onImageLoad = (e) => {
      imageLoaded.value = true;
      const info = uni.getImageInfo ? null : null;
      uni.getSystemInfo({
        success: (res) => {
          canvasWidth.value = res.windowWidth;
          canvasHeight.value = res.windowWidth * 0.75;
        }
      });
    };

    const drawCompositionLines = () => {
      const ctx = uni.createCanvasContext('composition-canvas');
      const w = canvasWidth.value;
      const h = canvasHeight.value;
      ctx.clearRect(0, 0, w, h);

      // 绘制三分法线
      ctx.setStrokeStyle('rgba(255, 255, 255, 0.5)');
      ctx.setLineWidth(1);
      ctx.setLineDash([8, 8]);

      // 垂直三分线
      [1 / 3, 2 / 3].forEach((ratio) => {
        ctx.beginPath();
        ctx.moveTo(w * ratio, 0);
        ctx.lineTo(w * ratio, h);
        ctx.stroke();
      });

      // 水平三分线
      [1 / 3, 2 / 3].forEach((ratio) => {
        ctx.beginPath();
        ctx.moveTo(0, h * ratio);
        ctx.lineTo(w, h * ratio);
        ctx.stroke();
      });

      // 标记三分交叉点
      ctx.setFillStyle('rgba(124, 106, 245, 0.8)');
      ctx.setLineDash([]);
      [[1 / 3, 1 / 3], [2 / 3, 1 / 3], [1 / 3, 2 / 3], [2 / 3, 2 / 3]].forEach(([rx, ry]) => {
        ctx.beginPath();
        ctx.arc(w * rx, h * ry, 8, 0, Math.PI * 2);
        ctx.fill();
      });

      // 标记主体位置
      const primary = detection.value?.primary_subject;
      if (primary) {
        const bbox = primary.bbox;
        ctx.setStrokeStyle('#fbbf24');
        ctx.setLineWidth(3);
        ctx.setLineDash([]);
        ctx.strokeRect(bbox.x * w, bbox.y * h, bbox.width * w, bbox.height * h);

        // 标记中心
        const cx = (bbox.x + bbox.width / 2) * w;
        const cy = (bbox.y + bbox.height / 2) * h;
        ctx.setStrokeStyle('#fbbf24');
        ctx.beginPath();
        ctx.moveTo(cx - 15, cy);
        ctx.lineTo(cx + 15, cy);
        ctx.moveTo(cx, cy - 15);
        ctx.lineTo(cx, cy + 15);
        ctx.stroke();
      }

      ctx.draw();
    };

    const selectCrop = (idx, crop) => {
      selectedCropIdx.value = idx;
      photoStore.applyCrop({
        x: crop.x, y: crop.y,
        width: crop.width, height: crop.height,
      });
    };

    const handleAutoCompose = async () => {
      if (autoLoading.value) return;
      autoLoading.value = true;
      loadingProgress.value = 0;

      // 模拟进度
      const timer = setInterval(() => {
        if (loadingProgress.value < 90) {
          loadingProgress.value += Math.random() * 15;
        }
      }, 500);

      try {
        const result = await photoAPI.autoCompose(currentImagePath.value);
        clearInterval(timer);
        loadingProgress.value = 100;

        const data = result.data;
        autoResult.value = {
          previewUrl: data.preview_full_url || data.preview_url,
          editSuggestions: data.edit_suggestions,
          composition: data.composition,
        };

        if (data.edit_suggestions) {
          photoStore.applySuggestions(data.edit_suggestions);
        }

        photoStore.addToHistory({
          imagePath: currentImagePath.value,
          score: data.composition?.total_score || 0,
          analysisResult: {
            detection: data.detection,
            composition: data.composition,
          },
        });

        uni.showToast({ title: 'AI 优化完成！', icon: 'success' });
      } catch (err) {
        clearInterval(timer);
        uni.showToast({ title: `优化失败: ${err.message}`, icon: 'error' });
      } finally {
        autoLoading.value = false;
      }
    };

    const goToEditor = () => {
      uni.navigateTo({
        url: '/pages/editor/index',
        animationType: 'slide-in-right',
      });
    };

    const shareImage = async () => {
      const url = autoResult.value?.previewUrl;
      if (!url) return;
      try {
        await photoAPI.saveToAlbum(url);
        uni.showToast({ title: '已保存到相册', icon: 'success' });
      } catch (err) {
        uni.showToast({ title: err.message, icon: 'error' });
      }
    };

    onMounted(() => {
      if (composition.value && showOverlay.value) {
        nextTick(drawCompositionLines);
      }
    });

    return {
      currentImagePath,
      detection,
      composition,
      showOverlay,
      imageLoaded,
      canvasWidth,
      canvasHeight,
      selectedCropIdx,
      autoLoading,
      autoResult,
      loadingProgress,
      scoreLevel,
      dimensionScores,
      topObjects,
      getCropRatioText,
      toggleOverlay,
      onImageLoad,
      selectCrop,
      handleAutoCompose,
      goToEditor,
      shareImage,
    };
  },
};
</script>

<style lang="scss" scoped>
.result-page {
  min-height: 100vh;
  background: #0f0f1a;
}

.result-scroll {
  height: 100vh;
}

/* 图片区 */
.image-section {
  position: relative;
  background: #000;
}

.image-container {
  position: relative;
  width: 100%;
  min-height: 400rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.main-image {
  width: 100%;
  max-height: 60vw;
}

.composition-canvas {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
}

.tap-hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.3);
  padding: 12rpx 0;
  background: #0f0f1a;
}

/* 评分卡 */
.score-section {
  margin: 30rpx 30rpx 0;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 24rpx;
  padding: 32rpx;
}

.score-header { margin-bottom: 32rpx; }

.score-main {
  display: flex;
  align-items: center;
  gap: 30rpx;
}

.score-ring {
  width: 120rpx;
  height: 120rpx;
  border-radius: 50%;
  background: conic-gradient(#7c6af5 0%, #a855f7 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  flex-shrink: 0;
  box-shadow: 0 0 30rpx rgba(124, 106, 245, 0.4);
}

.score-num {
  font-size: 44rpx;
  font-weight: 800;
  color: white;
  line-height: 1;
}

.score-unit {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.7);
}

.score-label {
  display: block;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.5);
  margin-bottom: 6rpx;
}

.score-type {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: #fff;
  margin-bottom: 12rpx;
}

.score-badge {
  display: inline-flex;
  padding: 6rpx 20rpx;
  border-radius: 20rpx;
  font-size: 22rpx;
}

.badge-gold { background: rgba(251, 191, 36, 0.2); border: 1px solid rgba(251, 191, 36, 0.5); }
.badge-blue { background: rgba(124, 106, 245, 0.2); border: 1px solid rgba(124, 106, 245, 0.5); }
.badge-gray { background: rgba(107, 114, 128, 0.2); border: 1px solid rgba(107, 114, 128, 0.5); }
.badge-red { background: rgba(239, 68, 68, 0.2); border: 1px solid rgba(239, 68, 68, 0.5); }

.score-badge-text { color: #fff; }

/* 维度评分条 */
.dimensions {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
}

.dim-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8rpx;
}

.dim-name {
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.7);
}

.dim-value {
  font-size: 24rpx;
  color: #fff;
  font-weight: 600;
}

.dim-bar {
  height: 8rpx;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 10rpx;
  overflow: hidden;
}

.dim-fill {
  height: 100%;
  border-radius: 10rpx;
  transition: width 0.5s ease;
}

/* 建议 */
.suggestions-section {
  margin: 30rpx 30rpx 0;
}

.section-title {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: #fff;
  margin-bottom: 24rpx;
}

.suggestion-list {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20rpx;
  padding: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.suggestion-item {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
}

.s-icon { font-size: 28rpx; flex-shrink: 0; }

.s-text {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.75);
  line-height: 1.5;
}

/* 检测标签 */
.detection-section { margin: 30rpx 30rpx 0; }

.detection-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.det-tag {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: rgba(124, 106, 245, 0.12);
  border: 1px solid rgba(124, 106, 245, 0.3);
  border-radius: 30rpx;
  padding: 10rpx 20rpx;
}

.face-tag {
  background: rgba(236, 72, 153, 0.12);
  border-color: rgba(236, 72, 153, 0.3);
}

.det-icon { font-size: 24rpx; }
.det-label { font-size: 24rpx; color: #fff; }
.det-conf { font-size: 22rpx; color: rgba(255,255,255,0.5); }

/* 裁剪方案 */
.crops-section { margin: 30rpx 0 0; }

.crops-section .section-title { padding: 0 30rpx; }

.crops-scroll { padding-left: 30rpx; }

.crops-list {
  display: flex;
  gap: 20rpx;
  padding-right: 30rpx;
}

.crop-card {
  flex-shrink: 0;
  width: 220rpx;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 20rpx;
  overflow: hidden;
  transition: border-color 0.2s;
}

.crop-selected {
  border-color: #7c6af5;
  box-shadow: 0 0 20rpx rgba(124, 106, 245, 0.3);
}

.crop-preview {
  position: relative;
  height: 160rpx;
  overflow: hidden;
}

.crop-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.crop-overlay-info {
  position: absolute;
  bottom: 0;
  right: 0;
  background: rgba(0,0,0,0.6);
  padding: 6rpx 12rpx;
  border-radius: 10rpx 0 0 0;
}

.crop-ratio { font-size: 20rpx; color: #fff; font-weight: 700; }

.crop-name {
  display: block;
  font-size: 24rpx;
  font-weight: 700;
  color: #fff;
  padding: 12rpx 16rpx 4rpx;
}

.crop-desc {
  display: block;
  font-size: 20rpx;
  color: rgba(255,255,255,0.4);
  padding: 0 16rpx 16rpx;
  line-height: 1.4;
}

/* 操作按钮 */
.actions-section {
  margin: 40rpx 30rpx 0;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.btn-row { display: flex; gap: 20rpx; }

.secondary-row { }

.btn-auto {
  flex: 1;
  background: linear-gradient(135deg, #7c6af5, #a855f7);
  border-radius: 20rpx;
  padding: 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  box-shadow: 0 8rpx 30rpx rgba(124, 106, 245, 0.4);
}

.btn-auto:active { opacity: 0.85; }
.btn-auto.loading { opacity: 0.7; }

.btn-edit, .btn-share {
  flex: 1;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  border-radius: 20rpx;
  padding: 28rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
}

.btn-edit:active, .btn-share:active { opacity: 0.8; }

.btn-icon { font-size: 32rpx; }
.btn-text { font-size: 28rpx; font-weight: 600; color: #fff; }

/* 对比区 */
.preview-compare {
  margin: 30rpx 30rpx 0;
}

.compare-wrap {
  display: flex;
  align-items: center;
  gap: 16rpx;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 20rpx;
  padding: 20rpx;
  margin-bottom: 20rpx;
}

.compare-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}

.compare-img {
  width: 100%;
  height: 200rpx;
  border-radius: 12rpx;
  object-fit: cover;
}

.compare-label {
  font-size: 22rpx;
  color: rgba(255,255,255,0.5);
}

.compare-arrow {
  font-size: 40rpx;
  color: #7c6af5;
  flex-shrink: 0;
}

.applied-tags {
  background: rgba(124, 106, 245, 0.1);
  border: 1px solid rgba(124, 106, 245, 0.2);
  border-radius: 16rpx;
  padding: 20rpx;
}

.applied-title {
  display: block;
  font-size: 24rpx;
  color: rgba(255,255,255,0.6);
  margin-bottom: 12rpx;
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.applied-tag {
  background: rgba(124, 106, 245, 0.2);
  border: 1px solid rgba(124, 106, 245, 0.4);
  border-radius: 20rpx;
  padding: 6rpx 16rpx;
  font-size: 22rpx;
  color: #c4b5fd;
}

/* 加载 */
.loading-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 15, 26, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}

.loading-box {
  width: 550rpx;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 30rpx;
  padding: 60rpx 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;
}

.ai-loading-icon { font-size: 80rpx; }

.loading-title {
  font-size: 34rpx;
  font-weight: 700;
  color: #fff;
}

.loading-sub {
  font-size: 24rpx;
  color: rgba(255,255,255,0.5);
  text-align: center;
}

.loading-progress {
  width: 100%;
  height: 8rpx;
  background: rgba(255,255,255,0.1);
  border-radius: 10rpx;
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #7c6af5, #a855f7, #ec4899);
  border-radius: 10rpx;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 24rpx;
  color: #7c6af5;
  font-weight: 600;
}
</style>
