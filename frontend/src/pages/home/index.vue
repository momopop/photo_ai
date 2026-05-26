<template>
  <view class="home-page">
    <!-- 顶部标题区 -->
    <view class="header">
      <view class="header-top">
        <view class="logo-area">
          <view class="logo-icon">
            <text class="icon-text">✦</text>
          </view>
          <view>
            <text class="app-title">PhotoAI</text>
            <text class="app-subtitle">AI 智能构图 · 自动修图</text>
          </view>
        </view>
        <view class="service-dot" :class="serviceStatus === 'ok' ? 'dot-green' : 'dot-red'" @tap="checkService">
          <text class="dot-text">{{ serviceStatus === 'ok' ? '在线' : '离线' }}</text>
        </view>
      </view>
    </view>

    <!-- 主要内容区 -->
    <scroll-view class="main-scroll" scroll-y>
      <!-- Hero 区 -->
      <view class="hero-section">
        <view class="hero-bg">
          <view class="hero-circle c1"></view>
          <view class="hero-circle c2"></view>
          <view class="hero-circle c3"></view>
        </view>
        <text class="hero-title">用 AI 让每张照片</text>
        <text class="hero-title gradient-text">都是大片</text>
        <text class="hero-desc">智能构图分析 · 一键自动修图 · 专业级效果</text>
      </view>

      <!-- 功能入口 -->
      <view class="action-section">
        <!-- 拍照按钮 -->
        <view class="action-card primary-card" @tap="handleTakePhoto">
          <view class="action-icon-wrap">
            <text class="action-icon">📷</text>
          </view>
          <view class="action-text-wrap">
            <text class="action-title">立即拍照</text>
            <text class="action-desc">使用摄像头拍摄，AI 实时分析</text>
          </view>
          <view class="action-arrow">›</view>
        </view>

        <!-- 相册选图 -->
        <view class="action-card secondary-card" @tap="handleChooseAlbum">
          <view class="action-icon-wrap">
            <text class="action-icon">🖼</text>
          </view>
          <view class="action-text-wrap">
            <text class="action-title">从相册选择</text>
            <text class="action-desc">导入照片进行 AI 优化</text>
          </view>
          <view class="action-arrow">›</view>
        </view>
      </view>

      <!-- 功能特性介绍 -->
      <view class="features-section">
        <text class="section-title">核心功能</text>
        <view class="features-grid">
          <view class="feature-item" v-for="feat in features" :key="feat.id">
            <view class="feature-icon-bg">
              <text class="feature-icon">{{ feat.icon }}</text>
            </view>
            <text class="feature-name">{{ feat.name }}</text>
            <text class="feature-detail">{{ feat.detail }}</text>
          </view>
        </view>
      </view>

      <!-- 使用流程 -->
      <view class="flow-section">
        <text class="section-title">使用流程</text>
        <view class="flow-steps">
          <view class="flow-step" v-for="(step, idx) in steps" :key="idx">
            <view class="step-num">{{ idx + 1 }}</view>
            <view class="step-content">
              <text class="step-title">{{ step.title }}</text>
              <text class="step-desc">{{ step.desc }}</text>
            </view>
            <view v-if="idx < steps.length - 1" class="step-connector"></view>
          </view>
        </view>
      </view>

      <!-- 最近记录 -->
      <view v-if="recentHistory.length > 0" class="recent-section">
        <text class="section-title">最近处理</text>
        <scroll-view scroll-x class="recent-scroll">
          <view class="recent-list">
            <view
              class="recent-item"
              v-for="item in recentHistory"
              :key="item.timestamp"
              @tap="openHistory(item)"
            >
              <image class="recent-thumb" :src="item.imagePath" mode="aspectFill" />
              <view class="recent-score-badge">
                <text class="recent-score">{{ item.score }}</text>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>

      <view style="height: 120rpx;"></view>
    </scroll-view>

    <!-- 加载遮罩 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-content">
        <view class="loading-spinner"></view>
        <text class="loading-text">{{ loadingText }}</text>
      </view>
    </view>
  </view>
</template>

<script>
import { ref, computed, onMounted } from 'vue';
import { usePhotoStore } from '../../store/photo';
import { photoAPI } from '../../api/photoai';

export default {
  name: 'HomePage',
  setup() {
    const photoStore = usePhotoStore();
    const loading = ref(false);
    const loadingText = ref('AI 分析中...');
    const serviceStatus = ref('checking');

    const features = [
      { id: 1, icon: '🎯', name: '目标检测', detail: 'YOLOv8 智能识别人物、景物' },
      { id: 2, icon: '📐', name: '构图分析', detail: '三分法、黄金比例综合评分' },
      { id: 3, icon: '✂️', name: '智能裁剪', detail: '多种构图方案一键对比' },
      { id: 4, icon: '🎨', name: 'AI 修图', detail: '自动色彩、光影、锐化优化' },
      { id: 5, icon: '⚡', name: '一键增强', detail: '自动白平衡 + HDR 增强' },
      { id: 6, icon: '💾', name: '保存分享', detail: '高质量导出到相册' },
    ];

    const steps = [
      { title: '选择照片', desc: '拍摄或从相册导入' },
      { title: 'AI 分析', desc: '检测主体，评估构图' },
      { title: '查看建议', desc: '构图评分 + 裁剪方案' },
      { title: '一键优化', desc: '自动修图 + 保存' },
    ];

    const recentHistory = computed(() => photoStore.history.slice(0, 8));

    const checkService = async () => {
      try {
        const res = await photoAPI.checkAIHealth();
        serviceStatus.value = res.ai_service === 'ok' ? 'ok' : 'error';
      } catch {
        serviceStatus.value = 'error';
      }
    };

    const processImage = async (imagePath) => {
      loading.value = true;
      loadingText.value = 'AI 分析构图中...';

      try {
        photoStore.setCurrentImage(imagePath);

        // 触发 AI 分析
        const result = await photoAPI.analyzeImage(imagePath);
        photoStore.analysisResult.detection = result.data?.detection;
        photoStore.analysisResult.composition = result.data?.composition;

        // 跳转到结果页
        uni.navigateTo({
          url: '/pages/result/index',
          animationType: 'slide-in-right',
        });
      } catch (err) {
        if (err.message === 'cancelled') return;
        uni.showToast({
          title: `分析失败: ${err.message}`,
          icon: 'error',
          duration: 3000,
        });
      } finally {
        loading.value = false;
      }
    };

    const handleTakePhoto = async () => {
      try {
        uni.showActionSheet({
          itemList: ['拍照', '从相册选择'],
          success: async (res) => {
            if (res.tapIndex === 0) {
              const path = await photoAPI.takePhoto();
              processImage(path);
            } else {
              const path = await photoAPI.chooseFromAlbum();
              processImage(path);
            }
          },
        });
      } catch (err) {
        if (err.message !== 'cancelled') {
          uni.showToast({ title: err.message, icon: 'error' });
        }
      }
    };

    const handleChooseAlbum = async () => {
      try {
        const path = await photoAPI.chooseFromAlbum();
        processImage(path);
      } catch (err) {
        if (err.message !== 'cancelled') {
          uni.showToast({ title: err.message, icon: 'error' });
        }
      }
    };

    const openHistory = (item) => {
      photoStore.setCurrentImage(item.imagePath);
      if (item.analysisResult) {
        photoStore.analysisResult.detection = item.analysisResult.detection;
        photoStore.analysisResult.composition = item.analysisResult.composition;
      }
      uni.navigateTo({ url: '/pages/result/index' });
    };

    onMounted(() => {
      checkService();
    });

    return {
      loading,
      loadingText,
      serviceStatus,
      features,
      steps,
      recentHistory,
      handleTakePhoto,
      handleChooseAlbum,
      openHistory,
      checkService,
    };
  },
};
</script>

<style lang="scss" scoped>
.home-page {
  min-height: 100vh;
  background: #0f0f1a;
  position: relative;
}

/* 顶部 Header */
.header {
  padding: 100rpx 40rpx 30rpx;
  background: linear-gradient(180deg, rgba(124, 106, 245, 0.15) 0%, transparent 100%);
}

.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 20rpx;
}

.logo-icon {
  width: 80rpx;
  height: 80rpx;
  background: linear-gradient(135deg, #7c6af5, #a855f7);
  border-radius: 20rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon-text {
  font-size: 40rpx;
  color: white;
}

.app-title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #fff;
  letter-spacing: 2rpx;
}

.app-subtitle {
  display: block;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.5);
  margin-top: 4rpx;
}

.service-dot {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 10rpx 20rpx;
  border-radius: 30rpx;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.dot-green { border-color: rgba(52, 211, 153, 0.4); }
.dot-red { border-color: rgba(248, 113, 113, 0.4); }

.dot-text {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.7);
}

/* Hero 区 */
.main-scroll { height: calc(100vh - 200rpx); }

.hero-section {
  position: relative;
  padding: 60rpx 40rpx 80rpx;
  overflow: hidden;
}

.hero-bg { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }

.hero-circle {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.15;
}

.c1 {
  width: 400rpx; height: 400rpx;
  background: #7c6af5;
  top: -100rpx; left: -100rpx;
}

.c2 {
  width: 300rpx; height: 300rpx;
  background: #a855f7;
  top: 50rpx; right: -50rpx;
}

.c3 {
  width: 200rpx; height: 200rpx;
  background: #ec4899;
  bottom: 0; left: 40%;
}

.hero-title {
  display: block;
  font-size: 68rpx;
  font-weight: 800;
  color: #fff;
  line-height: 1.2;
  position: relative;
}

.gradient-text {
  background: linear-gradient(135deg, #7c6af5, #a855f7, #ec4899);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.hero-desc {
  display: block;
  margin-top: 24rpx;
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.5);
  position: relative;
}

/* 操作入口 */
.action-section {
  padding: 0 40rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

.action-card {
  display: flex;
  align-items: center;
  padding: 32rpx;
  border-radius: 24rpx;
  gap: 24rpx;
  position: relative;
  overflow: hidden;
}

.action-card:active { opacity: 0.85; transform: scale(0.99); }

.primary-card {
  background: linear-gradient(135deg, #7c6af5 0%, #a855f7 100%);
  box-shadow: 0 12rpx 40rpx rgba(124, 106, 245, 0.4);
}

.secondary-card {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.action-icon-wrap {
  width: 100rpx;
  height: 100rpx;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.action-icon { font-size: 48rpx; }

.action-text-wrap { flex: 1; }

.action-title {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
  color: #fff;
}

.action-desc {
  display: block;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.65);
  margin-top: 6rpx;
}

.action-arrow {
  font-size: 48rpx;
  color: rgba(255, 255, 255, 0.4);
  font-weight: 300;
}

/* 特性网格 */
.features-section {
  padding: 60rpx 40rpx 0;
}

.section-title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #fff;
  margin-bottom: 32rpx;
}

.features-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 20rpx;
}

.feature-item {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20rpx;
  padding: 28rpx 16rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}

.feature-icon-bg {
  width: 80rpx;
  height: 80rpx;
  background: rgba(124, 106, 245, 0.15);
  border-radius: 20rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.feature-icon { font-size: 36rpx; }

.feature-name {
  font-size: 24rpx;
  font-weight: 600;
  color: #fff;
  text-align: center;
}

.feature-detail {
  font-size: 20rpx;
  color: rgba(255, 255, 255, 0.4);
  text-align: center;
  line-height: 1.4;
}

/* 流程 */
.flow-section {
  padding: 60rpx 40rpx 0;
}

.flow-steps {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.flow-step {
  display: flex;
  align-items: flex-start;
  gap: 24rpx;
  position: relative;
}

.step-num {
  width: 60rpx;
  height: 60rpx;
  background: linear-gradient(135deg, #7c6af5, #a855f7);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 700;
  color: white;
  flex-shrink: 0;
  z-index: 1;
}

.step-content {
  flex: 1;
  padding-bottom: 40rpx;
}

.step-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #fff;
}

.step-desc {
  display: block;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.5);
  margin-top: 6rpx;
}

.step-connector {
  position: absolute;
  left: 29rpx;
  top: 60rpx;
  width: 2rpx;
  height: 40rpx;
  background: linear-gradient(to bottom, #7c6af5, transparent);
}

/* 历史 */
.recent-section {
  padding: 60rpx 40rpx 0;
}

.recent-scroll { white-space: nowrap; }

.recent-list {
  display: flex;
  gap: 20rpx;
}

.recent-item {
  position: relative;
  flex-shrink: 0;
}

.recent-thumb {
  width: 180rpx;
  height: 180rpx;
  border-radius: 16rpx;
  object-fit: cover;
}

.recent-score-badge {
  position: absolute;
  bottom: 10rpx;
  right: 10rpx;
  background: rgba(124, 106, 245, 0.9);
  border-radius: 20rpx;
  padding: 4rpx 12rpx;
}

.recent-score {
  font-size: 22rpx;
  font-weight: 700;
  color: white;
}

/* 加载遮罩 */
.loading-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 15, 26, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}

.loading-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 30rpx;
  padding: 60rpx;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 30rpx;
}

.loading-spinner {
  width: 80rpx;
  height: 80rpx;
  border: 6rpx solid rgba(124, 106, 245, 0.2);
  border-top-color: #7c6af5;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  font-size: 28rpx;
  color: rgba(255, 255, 255, 0.7);
}
</style>
