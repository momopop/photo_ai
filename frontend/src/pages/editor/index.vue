<template>
  <view class="editor-page">

    <!-- ───── 顶部操作栏 ───── -->
    <view class="top-bar">
      <view class="tb-left">
        <view class="tb-btn" @tap="goBack">
          <text class="tb-icon">←</text>
          <text class="tb-label">返回</text>
        </view>
      </view>

      <view class="tb-right">
        <!-- 对比按钮：按住显示原图，松开显示修改后 -->
        <view
          class="tb-btn compare-btn"
          :class="{ 'compare-active': showOriginal }"
          @touchstart.prevent="showOriginal = true"
          @touchend.prevent="showOriginal = false"
          @touchcancel.prevent="showOriginal = false"
          @mousedown="showOriginal = true"
          @mouseup="showOriginal = false"
          @mouseleave="showOriginal = false"
        >
          <text class="tb-icon">{{ showOriginal ? '🔍' : '◑' }}</text>
          <text class="tb-label">{{ showOriginal ? '原图' : '对比' }}</text>
        </view>

        <view class="tb-btn ai-btn" @tap="applyAISuggestions">
          <text class="tb-icon">🤖</text>
          <text class="tb-label">AI建议</text>
        </view>

        <view class="tb-btn reset-btn" @tap="resetParams">
          <text class="tb-icon">↺</text>
          <text class="tb-label">重置</text>
        </view>

        <view class="tb-btn save-btn" :class="{ 'btn-disabled': saving }" @tap="saveResult">
          <text class="tb-icon">{{ saving ? '⏳' : '💾' }}</text>
          <text class="tb-label">{{ saving ? '保存中' : '保存' }}</text>
        </view>
      </view>
    </view>

    <!-- ───── 图片预览区 ───── -->
    <view class="preview-area">
      <view v-if="!imagePath" class="no-image" @tap="chooseImage">
        <text class="no-img-icon">🖼</text>
        <text class="no-img-text">点击上传图片</text>
      </view>

      <image
        v-else
        class="preview-image"
        :src="imagePath"
        mode="aspectFit"
        :style="previewStyle"
      />

      <!-- AI 建议应用动画提示 -->
      <view v-if="aiHint" class="ai-hint-badge">
        <text class="ai-hint-text">✨ AI建议已应用</text>
      </view>

      <!-- 保存中遮罩 -->
      <view v-if="saving" class="saving-mask">
        <view class="saving-spinner"></view>
        <text class="saving-text">正在生成高质量图片...</text>
      </view>
    </view>

    <!-- ───── 底部面板 ───── -->
    <view class="bottom-panel">
      <!-- Tab 栏 -->
      <view class="tab-bar">
        <view
          v-for="tab in toolTabs"
          :key="tab.id"
          class="tab-item"
          :class="{ 'tab-active': activeTab === tab.id }"
          @tap="activeTab = tab.id"
        >
          <text class="tab-icon">{{ tab.icon }}</text>
          <text class="tab-label">{{ tab.label }}</text>
        </view>
      </view>

      <!-- 滑块面板 -->
      <view class="sliders-panel">

        <!-- 光效 -->
        <view v-if="activeTab === 'light'" class="sliders-group">
          <SliderItem label="亮度" icon="☀️" v-model="params.brightness" :min="-100" :max="100" />
          <SliderItem label="对比度" icon="◐" v-model="params.contrast" :min="-100" :max="100" />
          <SliderItem label="曝光" icon="💡" v-model="params.exposure" :min="-100" :max="100" />
        </view>

        <!-- 色彩 -->
        <view v-if="activeTab === 'color'" class="sliders-group">
          <SliderItem label="饱和度" icon="🎨" v-model="params.saturation" :min="-100" :max="100" />
          <SliderItem label="色温" icon="🌡" v-model="params.temperature" :min="-100" :max="100" />
          <SliderItem label="色调" icon="🌈" v-model="params.hue" :min="-180" :max="180" />
        </view>

        <!-- 细节 -->
        <view v-if="activeTab === 'detail'" class="sliders-group">
          <SliderItem label="锐化" icon="🔍" v-model="params.sharpness" :min="-100" :max="100" />
          <view class="toggle-row">
            <view class="toggle-item" :class="{ 'toggle-on': params.denoise }" @tap="params.denoise = !params.denoise">
              <text class="toggle-icon">🔇</text>
              <text class="toggle-name">降噪</text>
              <view class="switch" :class="{ 'switch-on': params.denoise }">
                <view class="switch-thumb"></view>
              </view>
            </view>
            <view class="toggle-item" :class="{ 'toggle-on': params.auto_enhance }" @tap="params.auto_enhance = !params.auto_enhance">
              <text class="toggle-icon">⚡</text>
              <text class="toggle-name">自动增强</text>
              <view class="switch" :class="{ 'switch-on': params.auto_enhance }">
                <view class="switch-thumb"></view>
              </view>
            </view>
          </view>
        </view>

        <!-- 裁剪 -->
        <view v-if="activeTab === 'crop'" class="crop-panel">
          <text class="crop-hint">选择构图方案（需先分析图片）</text>
          <view v-if="cropProposals.length > 0" class="crop-list">
            <view
              v-for="(crop, idx) in cropProposals"
              :key="idx"
              class="crop-chip"
              :class="{ 'crop-chip-active': selectedCropIdx === idx }"
              @tap="selectCrop(idx, crop)"
            >
              <text class="crop-chip-icon">✂️</text>
              <text class="crop-chip-name">{{ crop.name }}</text>
            </view>
          </view>
          <view v-else class="crop-empty">
            <text class="crop-empty-text">请先在首页选图并进行 AI 分析</text>
          </view>
        </view>

      </view>
    </view>

  </view>
</template>

<script>
import { ref, reactive, computed, watch, onMounted } from 'vue';
import { usePhotoStore } from '../../store/photo';
import { photoAPI } from '../../api/photoai';
import SliderItem from '../../components/SliderItem.vue';

export default {
  name: 'EditorPage',
  components: { SliderItem },

  setup() {
    const photoStore = usePhotoStore();

    // 图片路径：优先使用 store 里的原始图，保存成功后切换为输出图
    const imagePath = ref(photoStore.currentImagePath || '');
    const originalPath = ref(photoStore.currentImagePath || '');
    const showOriginal = ref(false);
    const saving = ref(false);
    const aiHint = ref(false);
    const selectedCropIdx = ref(-1);
    const activeTab = ref('light');

    const toolTabs = [
      { id: 'light', label: '光效', icon: '☀️' },
      { id: 'color', label: '色彩', icon: '🎨' },
      { id: 'detail', label: '细节', icon: '🔍' },
      { id: 'crop', label: '裁剪', icon: '✂️' },
    ];

    const params = reactive({
      brightness: 0,
      contrast: 0,
      saturation: 0,
      sharpness: 0,
      exposure: 0,
      temperature: 0,
      hue: 0,
      denoise: false,
      auto_enhance: false,
    });

    const cropParam = ref(null);

    const cropProposals = computed(
      () => photoStore.analysisResult.composition?.crop_proposals || []
    );

    // ── CSS filter 实时预览 ──────────────────────────────────────────
    // 将 [-100, 100] 参数映射为 CSS filter
    const previewStyle = computed(() => {
      if (showOriginal.value) return '';

      const brightness = 1 + (params.brightness + params.exposure * 0.5) / 100;
      const contrast = 1 + params.contrast / 100;
      const saturate = Math.max(0, 1 + params.saturation / 100);
      // 色温用 sepia 近似：正值暖色、负值冷色
      const sepia = params.temperature > 0 ? params.temperature / 100 * 0.3 : 0;
      const hueRotate = params.hue;
      // 锐化正值用 drop-shadow 近似，负值用 blur
      const blur = params.sharpness < 0 ? Math.abs(params.sharpness) / 100 * 2 : 0;

      let filter = `brightness(${brightness.toFixed(3)}) contrast(${contrast.toFixed(3)}) saturate(${saturate.toFixed(3)})`;
      if (hueRotate) filter += ` hue-rotate(${hueRotate}deg)`;
      if (sepia) filter += ` sepia(${sepia.toFixed(3)})`;
      if (blur) filter += ` blur(${blur.toFixed(2)}px)`;

      return `filter: ${filter}; transition: filter 0.15s ease;`;
    });

    // ── 操作方法 ────────────────────────────────────────────────────
    const resetParams = () => {
      Object.assign(params, {
        brightness: 0, contrast: 0, saturation: 0,
        sharpness: 0, exposure: 0, temperature: 0,
        hue: 0, denoise: false, auto_enhance: false,
      });
      cropParam.value = null;
      selectedCropIdx.value = -1;
      // 重置后恢复原图
      imagePath.value = originalPath.value;
    };

    const applyAISuggestions = () => {
      const s = photoStore.editParams;
      if (!s || (s.brightness === 0 && !s.auto_enhance)) {
        uni.showToast({ title: '请先在首页进行 AI 分析', icon: 'none', duration: 2000 });
        return;
      }
      // 动态将建议值写入参数，CSS filter 会立即响应
      params.brightness = s.brightness || 0;
      params.contrast = s.contrast || 0;
      params.saturation = s.saturation || 0;
      params.sharpness = s.sharpness || 0;
      params.denoise = s.denoise || false;
      params.auto_enhance = true;
      // 显示提示角标
      aiHint.value = true;
      setTimeout(() => { aiHint.value = false; }, 2500);
    };

    const selectCrop = (idx, crop) => {
      selectedCropIdx.value = idx;
      cropParam.value = crop;
      photoStore.applyCrop({ x: crop.x, y: crop.y, width: crop.width, height: crop.height });
      uni.showToast({ title: `已选：${crop.name}`, icon: 'none', duration: 1200 });
    };

    const saveResult = async () => {
      if (!imagePath.value) {
        uni.showToast({ title: '请先上传图片', icon: 'none' });
        return;
      }
      if (saving.value) return;
      saving.value = true;

      try {
        const editOptions = {
          brightness: params.brightness,
          contrast: params.contrast,
          saturation: params.saturation,
          sharpness: params.sharpness,
          denoise: params.denoise,
          auto_enhance: params.auto_enhance,
          crop_x: 0, crop_y: 0, crop_w: 0, crop_h: 0,
        };
        if (cropParam.value) {
          editOptions.crop_x = cropParam.value.x;
          editOptions.crop_y = cropParam.value.y;
          editOptions.crop_w = cropParam.value.width;
          editOptions.crop_h = cropParam.value.height;
        }

        const result = await photoAPI.editImage(originalPath.value, editOptions);
        const data = result.data;
        const url = data.proxy_url || data.output_full_url || data.output_url;

        // 保存成功后切换显示输出图，清除 CSS filter（已由服务器处理）
        imagePath.value = url;
        // 重置调整参数，因为服务端已烘焙进图里
        Object.assign(params, {
          brightness: 0, contrast: 0, saturation: 0,
          sharpness: 0, exposure: 0, temperature: 0,
          hue: 0, denoise: false, auto_enhance: false,
        });

        uni.showModal({
          title: '保存成功',
          content: '是否保存到相册？',
          confirmText: '保存相册',
          cancelText: '稍后',
          success: async (res) => {
            if (res.confirm) {
              try {
                await photoAPI.saveToAlbum(url);
                uni.showToast({ title: '已保存到相册', icon: 'success' });
              } catch (e) {
                uni.showToast({ title: e.message, icon: 'error' });
              }
            }
          },
        });
      } catch (err) {
        uni.showToast({ title: `保存失败: ${err.message}`, icon: 'error', duration: 3000 });
      } finally {
        saving.value = false;
      }
    };

    const goBack = () => {
      // 清空当前图片状态，回到首页重新上传
      uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/index' }) });
    };

    const chooseImage = async () => {
      try {
        uni.showActionSheet({
          itemList: ['📷  拍照（原图）', '🖼  从相册选择（原图）'],
          success: async (res) => {
            try {
              const path = res.tapIndex === 0
                ? await photoAPI.takePhoto()
                : await photoAPI.chooseFromAlbum();
              imagePath.value = path;
              originalPath.value = path;
              photoStore.setCurrentImage(path);
              resetParams();
            } catch (e) {
              if (e.message !== 'cancelled') {
                uni.showToast({ title: e.message, icon: 'error' });
              }
            }
          },
        });
      } catch (err) {
        if (err.message !== 'cancelled') {
          uni.showToast({ title: err.message, icon: 'error' });
        }
      }
    };

    onMounted(() => {
      // 若 store 里有 AI 建议，自动填入但不提示
      const s = photoStore.editParams;
      if (s && s.auto_enhance) {
        params.brightness = s.brightness || 0;
        params.contrast = s.contrast || 0;
        params.saturation = s.saturation || 0;
        params.sharpness = s.sharpness || 0;
        params.denoise = s.denoise || false;
        params.auto_enhance = true;
      }
    });

    return {
      imagePath,
      showOriginal,
      saving,
      aiHint,
      activeTab,
      toolTabs,
      params,
      cropProposals,
      selectedCropIdx,
      previewStyle,
      resetParams,
      applyAISuggestions,
      selectCrop,
      saveResult,
      goBack,
      chooseImage,
    };
  },
};
</script>

<style lang="scss" scoped>
/* ── 整页布局：全屏，三段固定，绝不滚动 ── */
.editor-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #0a0a14;
  overflow: hidden;
}

/* ── 顶部操作栏 ── */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24rpx;
  padding-top: calc(env(safe-area-inset-top) + 16rpx);
  height: calc(env(safe-area-inset-top) + 96rpx);
  background: #131320;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  flex-shrink: 0;
  z-index: 10;
}

.tb-left { display: flex; align-items: center; }
.tb-right { display: flex; align-items: center; gap: 8rpx; }

.tb-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4rpx;
  padding: 10rpx 16rpx;
  border-radius: 14rpx;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  min-width: 88rpx;
}

.tb-btn:active { opacity: 0.7; }

.tb-icon { font-size: 30rpx; line-height: 1; }
.tb-label { font-size: 20rpx; color: rgba(255,255,255,0.65); white-space: nowrap; }

.compare-btn { border-color: rgba(124,106,245,0.3); }
.compare-active {
  background: rgba(124,106,245,0.25) !important;
  border-color: #7c6af5 !important;
}
.compare-active .tb-label { color: #c4b5fd !important; }

.ai-btn {
  background: rgba(168,85,247,0.12);
  border-color: rgba(168,85,247,0.3);
}

.save-btn {
  background: linear-gradient(135deg, rgba(124,106,245,0.3), rgba(168,85,247,0.3));
  border-color: rgba(124,106,245,0.5);
}

.save-btn .tb-label { color: #c4b5fd; }

.btn-disabled { opacity: 0.5; pointer-events: none; }

/* ── 图片预览区：弹性撑满中间 ── */
.preview-area {
  flex: 1;
  min-height: 0;
  position: relative;
  background: #000;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.preview-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.no-image {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  cursor: pointer;
}

.no-img-icon { font-size: 80rpx; opacity: 0.25; }
.no-img-text { font-size: 28rpx; color: rgba(255,255,255,0.25); }

/* AI 建议角标 */
.ai-hint-badge {
  position: absolute;
  top: 20rpx;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(124,106,245,0.9);
  border-radius: 30rpx;
  padding: 10rpx 28rpx;
  pointer-events: none;
}

.ai-hint-text { font-size: 24rpx; color: #fff; font-weight: 600; }

/* 保存遮罩 */
.saving-mask {
  position: absolute;
  inset: 0;
  background: rgba(10,10,20,0.75);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24rpx;
}

.saving-spinner {
  width: 64rpx;
  height: 64rpx;
  border: 5rpx solid rgba(124,106,245,0.2);
  border-top-color: #7c6af5;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.saving-text { font-size: 26rpx; color: rgba(255,255,255,0.7); }

/* ── 底部面板：固定高度 ── */
.bottom-panel {
  flex-shrink: 0;
  background: #131320;
  border-top: 1px solid rgba(255,255,255,0.07);
  padding-bottom: env(safe-area-inset-bottom);
}

/* Tab 栏 */
.tab-bar {
  display: flex;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}

.tab-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  padding: 16rpx 0 12rpx;
  position: relative;
}

.tab-item::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 25%;
  right: 25%;
  height: 4rpx;
  border-radius: 4rpx;
  background: transparent;
  transition: background 0.2s;
}

.tab-active::after { background: #7c6af5; }

.tab-icon { font-size: 30rpx; }
.tab-label { font-size: 20rpx; color: rgba(255,255,255,0.45); }
.tab-active .tab-label { color: #a78bfa; }

/* 滑块面板 */
.sliders-panel {
  padding: 16rpx 24rpx 8rpx;
}

.sliders-group {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}

/* 开关行 */
.toggle-row {
  display: flex;
  gap: 16rpx;
  margin-top: 4rpx;
}

.toggle-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 14rpx;
  padding: 16rpx 18rpx;
  transition: border-color 0.2s, background 0.2s;
}

.toggle-on {
  background: rgba(124,106,245,0.12);
  border-color: rgba(124,106,245,0.35);
}

.toggle-icon { font-size: 26rpx; flex-shrink: 0; }
.toggle-name { flex: 1; font-size: 24rpx; color: rgba(255,255,255,0.7); }

.switch {
  width: 72rpx;
  height: 36rpx;
  border-radius: 18rpx;
  background: rgba(255,255,255,0.12);
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}

.switch-on { background: #7c6af5; }

.switch-thumb {
  position: absolute;
  top: 4rpx;
  left: 4rpx;
  width: 28rpx;
  height: 28rpx;
  background: #fff;
  border-radius: 50%;
  transition: left 0.2s;
}

.switch-on .switch-thumb { left: 40rpx; }

/* 裁剪面板 */
.crop-panel { padding: 4rpx 0; }

.crop-hint {
  display: block;
  font-size: 22rpx;
  color: rgba(255,255,255,0.35);
  margin-bottom: 16rpx;
}

.crop-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.crop-chip {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 12rpx 22rpx;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 50rpx;
  font-size: 24rpx;
  color: rgba(255,255,255,0.7);
  transition: all 0.15s;
}

.crop-chip-active {
  background: rgba(124,106,245,0.2);
  border-color: #7c6af5;
  color: #c4b5fd;
}

.crop-chip-icon { font-size: 22rpx; }
.crop-chip-name { font-size: 22rpx; }

.crop-empty { padding: 30rpx 0; text-align: center; }
.crop-empty-text { font-size: 24rpx; color: rgba(255,255,255,0.25); }
</style>
