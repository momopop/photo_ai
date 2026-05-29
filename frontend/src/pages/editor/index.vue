<template>
  <view class="editor-page">

    <!-- ───── 顶部操作栏 ───── -->
    <view class="top-bar" :style="topBarPaddingStyle">
      <view class="tb-left">
        <view class="tb-btn" @tap="goBack">
          <text class="tb-icon">←</text>
          <text class="tb-label">返回</text>
        </view>
      </view>

      <view class="tb-right">
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
        v-else-if="!isCropMode"
        class="preview-image"
        :src="imagePath"
        mode="aspectFit"
        :style="previewStyle"
      />

      <template v-else>
        <image
          class="preview-image"
          :src="imagePath"
          mode="aspectFit"
        />
        <ManualCropOverlay
          ref="cropOverlayRef"
          :image-src="imagePath"
          v-model="manualCropRect"
          :aspect-ratio="cropAspectRatio"
          :rotation="cropTotalRotate"
          :flip-h="cropState.flipH"
          :flip-v="cropState.flipV"
          :skew-h="cropState.skewH"
          :skew-v="cropState.skewV"
        />
      </template>

      <view v-if="cropProcessing" class="saving-mask">
        <view class="saving-spinner"></view>
        <text class="saving-text">处理中...</text>
      </view>

      <view v-if="aiHint" class="ai-hint-badge">
        <text class="ai-hint-text">✨ AI建议已应用</text>
      </view>

      <view v-if="saving" class="saving-mask">
        <view class="saving-spinner"></view>
        <text class="saving-text">正在生成高质量图片...</text>
      </view>
    </view>

    <!-- ───── 底部功能区：两大模块 + 横滑功能 + 子菜单 ───── -->
    <view class="bottom-panel" :class="{ 'bottom-expanded': !!activeToolId }">

      <!-- 子菜单（点击功能按钮后展开） -->
      <view v-if="activeToolId" class="submenu-panel">
        <view class="submenu-header">
          <text class="submenu-title">{{ activeToolLabel }}</text>
          <view class="submenu-close" @tap="closeSubmenu">
            <text>收起</text>
          </view>
        </view>

        <scroll-view scroll-y class="submenu-body" :show-scrollbar="false">

          <!-- 调色 -->
          <view v-if="activeToolId === 'tone'" class="submenu-content">
            <SliderItem label="亮度" icon="☀️" v-model="params.brightness" />
            <SliderItem label="对比度" icon="◐" v-model="params.contrast" />
            <SliderItem label="曝光" icon="💡" v-model="params.exposure" />
            <SliderItem label="高光" icon="✨" v-model="params.highlights" />
            <SliderItem label="阴影" icon="🌑" v-model="params.shadows" />
            <SliderItem label="饱和度" icon="🎨" v-model="params.saturation" />
            <SliderItem label="色温" icon="🌡" v-model="params.temperature" />
            <SliderItem label="色调" icon="🌈" v-model="params.hue" :min="-180" :max="180" />
            <SliderItem label="锐化" icon="🔍" v-model="params.sharpness" />
            <SliderItem label="清晰度" icon="◈" v-model="params.clarity" />
            <view class="curve-placeholder" @tap="onComingSoon('曲线')">
              <text class="curve-icon">📈</text>
              <text class="curve-text">曲线调节（即将上线）</text>
            </view>
            <view class="toggle-row">
              <view class="toggle-item" :class="{ 'toggle-on': params.denoise }" @tap="params.denoise = !params.denoise">
                <text class="toggle-icon">🔇</text>
                <text class="toggle-name">降噪</text>
                <view class="switch" :class="{ 'switch-on': params.denoise }"><view class="switch-thumb" /></view>
              </view>
            </view>
          </view>

          <!-- 滤镜 -->
          <view v-else-if="activeToolId === 'filter'" class="submenu-content filter-content">
            <scroll-view scroll-x class="filter-scroll" :show-scrollbar="false">
              <view class="filter-row">
                <view
                  v-for="f in filterPresets"
                  :key="f.id"
                  class="filter-chip"
                  :class="{ 'filter-chip-active': activeFilterId === f.id }"
                  @tap="selectFilter(f.id)"
                >
                  <view class="filter-thumb" :style="f.thumbStyle" />
                  <text class="filter-name">{{ f.name }}</text>
                </view>
              </view>
            </scroll-view>
          </view>

          <!-- 马赛克 -->
          <view v-else-if="activeToolId === 'mosaic'" class="submenu-content">
            <SliderItem label="马赛克强度" icon="▦" v-model="params.mosaic" :min="0" :max="100" />
            <text class="submenu-hint">预览为近似效果，保存时将请求服务端处理</text>
          </view>

          <!-- 背景虚化 -->
          <view v-else-if="activeToolId === 'blur_bg'" class="submenu-content">
            <SliderItem label="虚化强度" icon="◎" v-model="params.blur_bg" :min="0" :max="100" />
          </view>

          <!-- 裁切：参考专业修图裁切面板 -->
          <view v-else-if="activeToolId === 'crop'" class="submenu-content crop-panel">
            <view class="crop-quick-bar">
              <view class="crop-icon-btn" @tap="toggleFlipH">
                <text class="crop-q-icon">⇋</text>
              </view>
              <view class="crop-icon-btn" @tap="rotate90Step">
                <text class="crop-q-icon">↻</text>
              </view>
              <view class="crop-pill-btn" @tap="restoreCropSession">
                <text>还原</text>
              </view>
              <view class="crop-pill-btn" :class="{ 'crop-pill-active': showRatioPicker }" @tap="showRatioPicker = !showRatioPicker">
                <text>画幅</text>
              </view>
            </view>

            <view v-if="showRatioPicker" class="ratio-picker">
              <view
                v-for="p in cropRatioPresets"
                :key="p.id"
                class="ratio-chip"
                :class="{ 'ratio-chip-active': cropRatioId === p.id }"
                @tap="setCropRatio(p)"
              >
                <text>{{ p.label }}</text>
              </view>
            </view>

            <scroll-view scroll-x class="crop-mode-scroll" :show-scrollbar="false">
              <view class="crop-mode-row">
                <view
                  v-for="m in cropSubModes"
                  :key="m.id"
                  class="crop-mode-card"
                  :class="{ 'crop-mode-active': cropState.subMode === m.id }"
                  @tap="selectCropSubMode(m.id)"
                >
                  <text class="crop-mode-icon">{{ m.icon }}</text>
                  <text class="crop-mode-label">{{ m.label }}</text>
                  <text v-if="m.id === 'rotate'" class="crop-mode-value">{{ cropAngleDisplay }}</text>
                </view>
              </view>
            </scroll-view>

            <view v-if="cropState.subMode === 'rotate'" class="rotate-ruler-wrap">
              <view class="ruler-scale">
                <text v-for="n in rulerTicks" :key="n" class="ruler-tick">{{ n }}</text>
              </view>
              <slider
                class="rotate-slider"
                :value="cropState.rotate"
                :min="-45"
                :max="45"
                :step="1"
                activeColor="#f5c542"
                backgroundColor="rgba(255,255,255,0.12)"
                block-color="#f5c542"
                :block-size="22"
                @changing="onRotateChanging"
                @change="onRotateChange"
              />
            </view>

            <view v-else-if="cropState.subMode === 'horizontal'" class="param-slider-wrap">
              <SliderItem label="水平" icon="↔" v-model="cropState.skewH" :min="-30" :max="30" />
            </view>
            <view v-else-if="cropState.subMode === 'vertical'" class="param-slider-wrap">
              <SliderItem label="垂直" icon="↕" v-model="cropState.skewV" :min="-30" :max="30" />
            </view>
            <view v-else-if="cropState.subMode === 'distort'" class="param-slider-wrap">
              <SliderItem label="扭曲" icon="◇" v-model="cropState.distort" :min="-30" :max="30" />
            </view>
            <view v-else class="crop-free-hint">
              <text>自由裁切：拖动框体移动，拖四角缩放</text>
            </view>
          </view>

          <!-- AI 消除 -->
          <view v-else-if="activeToolId === 'ai_erase'" class="submenu-content">
            <view class="action-card" @tap="onComingSoon('AI 消除')">
              <text class="action-card-icon">🪄</text>
              <text class="action-card-title">智能涂抹消除</text>
              <text class="action-card-desc">圈选杂物后一键消除，功能即将上线</text>
            </view>
          </view>

          <!-- 人像：通用滑块子项 -->
          <view v-else-if="isPortraitTool" class="submenu-content">
            <SliderItem
              :label="activeToolLabel"
              :icon="activeToolIcon"
              v-model="portraitParams[activeToolId]"
              :min="0"
              :max="100"
            />
            <text class="submenu-hint">人像效果为预览示意，保存时将逐步接入服务端模型</text>
          </view>

        </scroll-view>
      </view>

      <!-- 功能按钮横滑（子菜单展开后可左右滑动切换） -->
      <scroll-view
        scroll-x
        class="tools-scroll"
        :show-scrollbar="false"
        :scroll-into-view="toolScrollIntoView"
        scroll-with-animation
      >
        <view class="tools-row">
          <view
            v-for="tool in currentTools"
            :key="tool.id"
            :id="'tool-' + tool.id"
            class="tool-btn"
            :class="{ 'tool-btn-active': activeToolId === tool.id }"
            @tap="selectTool(tool.id)"
          >
            <text class="tool-icon">{{ tool.icon }}</text>
            <text class="tool-label">{{ tool.label }}</text>
          </view>
        </view>
      </scroll-view>

      <!-- 两大模块切换 -->
      <view class="module-bar">
        <view
          v-for="mod in editorModules"
          :key="mod.id"
          class="module-tab"
          :class="{ 'module-tab-active': activeModuleId === mod.id }"
          @tap="switchModule(mod.id)"
        >
          <text class="module-tab-text">{{ mod.label }}</text>
        </view>
      </view>
    </view>

    <!-- 离屏 Canvas：裁切 / 旋转 -->
    <canvas
      canvas-id="imgProcCanvas"
      id="imgProcCanvas"
      class="proc-canvas"
      :style="{ width: canvasPx.w + 'px', height: canvasPx.h + 'px' }"
      :width="canvasPx.w"
      :height="canvasPx.h"
    />
  </view>
</template>

<script>
import { ref, reactive, computed, onMounted, getCurrentInstance, nextTick } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import { usePhotoStore } from '../../store/photo';
import { photoAPI, toAppImageSrc } from '../../api/photoai';
import SliderItem from '../../components/SliderItem.vue';
import ManualCropOverlay, { DEFAULT_CROP } from '../../components/ManualCropOverlay.vue';
import {
  getImageInfo,
  applyCropPipeline,
  isFullCrop,
} from '../../utils/imageCrop';

/** 图片美化功能 */
const IMAGE_TOOLS = [
  { id: 'tone', label: '调色', icon: '🎨' },
  { id: 'filter', label: '滤镜', icon: '✨' },
  { id: 'mosaic', label: '马赛克', icon: '▦' },
  { id: 'blur_bg', label: '背景虚化', icon: '◎' },
  { id: 'crop', label: '裁切', icon: '✂️' },
  { id: 'ai_erase', label: 'AI消除', icon: '🪄' },
];

/** 人像美化功能 */
const PORTRAIT_TOOLS = [
  { id: 'face_reshape', label: '面部重塑', icon: '👤' },
  { id: 'slim_face', label: '瘦脸', icon: '◯' },
  { id: 'hair', label: '美发', icon: '💇' },
  { id: 'skin_smooth', label: '磨皮', icon: '✧' },
  { id: 'wrinkle', label: '祛皱', icon: '〰' },
  { id: 'oil_remove', label: '去油光', icon: '💧' },
  { id: 'portrait_erase', label: '消除', icon: '🧹' },
];

const CROP_RATIO_PRESETS = [
  { id: 'free', label: '自由', ratio: null },
  { id: '1_1', label: '1:1', ratio: 1 },
  { id: '3_4', label: '3:4', ratio: 3 / 4 },
  { id: '4_3', label: '4:3', ratio: 4 / 3 },
  { id: '9_16', label: '9:16', ratio: 9 / 16 },
  { id: '16_9', label: '16:9', ratio: 16 / 9 },
];

const CROP_SUB_MODES = [
  { id: 'rotate', label: '旋转', icon: '⟳' },
  { id: 'horizontal', label: '水平', icon: '↔' },
  { id: 'vertical', label: '垂直', icon: '↕' },
  { id: 'distort', label: '扭曲', icon: '◇' },
  { id: 'free', label: '自由', icon: '▢' },
];

const DEFAULT_CROP_STATE = () => ({
  rotate: 0,
  rotate90: 0,
  flipH: false,
  flipV: false,
  skewH: 0,
  skewV: 0,
  distort: 0,
  subMode: 'rotate',
});

const FILTER_PRESETS = [
  { id: 'none', name: '原图', thumbStyle: '', extra: '' },
  { id: 'vivid', name: '鲜艳', thumbStyle: 'filter: saturate(1.5) contrast(1.12)', extra: 'saturate(1.45) contrast(1.1) brightness(1.02)' },
  { id: 'natural', name: '自然', thumbStyle: 'filter: saturate(1.08) contrast(1.02)', extra: 'saturate(1.08) contrast(1.02) brightness(1.03)' },
  { id: 'negative', name: '负片', thumbStyle: 'filter: invert(0.85) hue-rotate(180deg)', extra: 'invert(1) hue-rotate(180deg) contrast(1.05)' },
  { id: 'lively', name: '生动', thumbStyle: 'filter: saturate(1.35) contrast(1.15) brightness(1.05)', extra: 'saturate(1.35) contrast(1.15) brightness(1.05)' },
  { id: 'warm', name: '暖色', thumbStyle: 'filter: sepia(0.25) saturate(1.2)', extra: 'sepia(0.2) saturate(1.15) brightness(1.04)' },
  { id: 'cool', name: '冷色', thumbStyle: 'filter: hue-rotate(15deg) saturate(0.9) brightness(1.02)', extra: 'hue-rotate(12deg) saturate(0.92) brightness(1.02)' },
  { id: 'bw', name: '黑白', thumbStyle: 'filter: grayscale(1) contrast(1.1)', extra: 'grayscale(1) contrast(1.12)' },
  { id: 'film', name: '胶片', thumbStyle: 'filter: sepia(0.35) contrast(1.08) saturate(0.85)', extra: 'sepia(0.3) contrast(1.1) saturate(0.88)' },
];

export default {
  name: 'EditorPage',
  components: { SliderItem, ManualCropOverlay },

  setup() {
    const photoStore = usePhotoStore();
    const vueInstance = getCurrentInstance();

    const statusBarHeight = ref(0);
    try {
      const si = uni.getSystemInfoSync();
      statusBarHeight.value = si.statusBarHeight || 0;
    } catch (e) { /* ignore */ }

    const topBarPaddingStyle = computed(() => ({
      paddingTop: (statusBarHeight.value + 8) + 'px',
      height: (statusBarHeight.value + 68) + 'px',
    }));

    const rawImagePath = ref(photoStore.currentImagePath || '');
    const imagePath = ref(toAppImageSrc(photoStore.currentImagePath || ''));
    const originalPath = ref(rawImagePath.value);
    /** 进入修图页时的基准图（右上角重置恢复至此） */
    const baselinePath = ref(rawImagePath.value);
    const showOriginal = ref(false);
    const saving = ref(false);
    const aiHint = ref(false);
    const cropOverlayRef = ref(null);
    const manualCropRect = ref(DEFAULT_CROP());
    const cropRatioId = ref('free');
    const cropAspectRatio = ref(null);
    const cropProcessing = ref(false);
    const canvasPx = ref({ w: 100, h: 100 });
    const cropRatioPresets = CROP_RATIO_PRESETS;
    const cropSubModes = CROP_SUB_MODES;
    const cropState = reactive(DEFAULT_CROP_STATE());
    const cropSessionSnapshot = ref(null);
    const showRatioPicker = ref(false);
    const rulerTicks = [-45, -30, -15, 0, 15, 30, 45];

    const cropTotalRotate = computed(
      () => cropState.rotate90 * 90 + cropState.rotate
    );
    const cropAngleDisplay = computed(() => {
      const v = Math.round(cropTotalRotate.value);
      return v > 0 ? `+${v}` : String(v);
    });

    const hasPendingCropEdits = () => {
      if (cropState.flipH || cropState.flipV) return true;
      if (cropState.rotate !== 0 || cropState.rotate90 !== 0) return true;
      if (cropState.skewH !== 0 || cropState.skewV !== 0 || cropState.distort !== 0) return true;
      if (!isFullCrop(manualCropRect.value)) return true;
      return false;
    };

    const editorModules = [
      { id: 'image', label: '图片美化' },
      { id: 'portrait', label: '人像美化' },
    ];
    const activeModuleId = ref('image');
    const activeToolId = ref('');
    const toolScrollIntoView = ref('');
    const activeFilterId = ref('none');

    const params = reactive({
      brightness: 0,
      contrast: 0,
      saturation: 0,
      sharpness: 0,
      exposure: 0,
      temperature: 0,
      hue: 0,
      highlights: 0,
      shadows: 0,
      clarity: 0,
      mosaic: 0,
      blur_bg: 0,
      denoise: false,
      auto_enhance: false,
    });

    const portraitParams = reactive({
      face_reshape: 0,
      slim_face: 0,
      hair: 0,
      skin_smooth: 0,
      wrinkle: 0,
      oil_remove: 0,
      portrait_erase: 0,
    });

    const filterPresets = FILTER_PRESETS;

    const isCropMode = computed(() => activeToolId.value === 'crop' && !!imagePath.value);

    const currentTools = computed(() =>
      activeModuleId.value === 'portrait' ? PORTRAIT_TOOLS : IMAGE_TOOLS
    );

    const activeToolMeta = computed(() =>
      currentTools.value.find((t) => t.id === activeToolId.value) || null
    );

    const activeToolLabel = computed(() => activeToolMeta.value?.label || '');
    const activeToolIcon = computed(() => activeToolMeta.value?.icon || '✨');

    const isPortraitTool = computed(() =>
      PORTRAIT_TOOLS.some((t) => t.id === activeToolId.value)
    );

    const previewStyle = computed(() => {
      if (showOriginal.value || isCropMode.value) return '';

      const brightness = 1 + (params.brightness + params.exposure * 0.5 + params.highlights * 0.15 - params.shadows * 0.1) / 100;
      const contrast = 1 + (params.contrast + params.clarity * 0.25) / 100;
      const saturate = Math.max(0, 1 + params.saturation / 100);
      const sepia = params.temperature > 0 ? params.temperature / 100 * 0.3 : 0;
      const hueRotate = params.hue;
      const blur = (params.sharpness < 0 ? Math.abs(params.sharpness) / 100 * 2 : 0)
        + params.blur_bg / 100 * 6
        + params.mosaic / 100 * 1.5;

      let filter = `brightness(${brightness.toFixed(3)}) contrast(${contrast.toFixed(3)}) saturate(${saturate.toFixed(3)})`;
      if (hueRotate) filter += ` hue-rotate(${hueRotate}deg)`;
      if (sepia) filter += ` sepia(${sepia.toFixed(3)})`;
      if (blur) filter += ` blur(${blur.toFixed(2)}px)`;

      const preset = FILTER_PRESETS.find((f) => f.id === activeFilterId.value);
      if (preset?.extra) filter += ` ${preset.extra}`;

      // 人像预览：轻微柔化示意
      const portraitBlur =
        (portraitParams.skin_smooth + portraitParams.wrinkle) / 100 * 0.8;
      if (portraitBlur > 0) filter += ` blur(${portraitBlur.toFixed(2)}px)`;

      return `filter: ${filter}; transition: filter 0.15s ease;`;
    });

    const switchModule = (moduleId) => {
      if (activeModuleId.value === moduleId) return;
      activeModuleId.value = moduleId;
      activeToolId.value = '';
      toolScrollIntoView.value = '';
    };

    const selectTool = (toolId) => {
      if (activeToolId.value === toolId) {
        closeSubmenu();
        return;
      }
      activeToolId.value = toolId;
      toolScrollIntoView.value = 'tool-' + toolId;
      if (toolId === 'crop') {
        snapshotCropSession();
        showRatioPicker.value = false;
        nextTick(() => cropOverlayRef.value?.resetCropBox?.());
      }
    };

    const snapshotCropSession = () => {
      cropSessionSnapshot.value = {
        path: rawImagePath.value,
        cropRect: { ...manualCropRect.value },
        cropState: { ...cropState, subMode: cropState.subMode },
        cropRatioId: cropRatioId.value,
        cropAspectRatio: cropAspectRatio.value,
      };
      Object.assign(cropState, DEFAULT_CROP_STATE());
      manualCropRect.value = DEFAULT_CROP();
      cropRatioId.value = 'free';
      cropAspectRatio.value = null;
    };

    const closeSubmenu = () => {
      activeToolId.value = '';
      toolScrollIntoView.value = '';
    };

    const selectFilter = (id) => {
      activeFilterId.value = id;
    };

    const onComingSoon = (name) => {
      uni.showToast({ title: `${name}功能即将上线`, icon: 'none' });
    };

    const resetCropStateOnly = () => {
      Object.assign(cropState, DEFAULT_CROP_STATE());
      manualCropRect.value = DEFAULT_CROP();
      cropRatioId.value = 'free';
      cropAspectRatio.value = null;
      showRatioPicker.value = false;
      nextTick(() => cropOverlayRef.value?.resetCropBox?.());
    };

    const resetAllParams = (silent = false) => {
      Object.assign(params, {
        brightness: 0, contrast: 0, saturation: 0,
        sharpness: 0, exposure: 0, temperature: 0,
        hue: 0, highlights: 0, shadows: 0, clarity: 0,
        mosaic: 0, blur_bg: 0,
        denoise: false, auto_enhance: false,
      });
      Object.keys(portraitParams).forEach((k) => { portraitParams[k] = 0; });
      activeFilterId.value = 'none';
      resetCropStateOnly();
      const base = baselinePath.value || originalPath.value;
      rawImagePath.value = base;
      originalPath.value = base;
      imagePath.value = toAppImageSrc(base);
      photoStore.setCurrentImage(base);
      cropSessionSnapshot.value = null;
      if (!silent) {
        uni.showToast({ title: '已重置全部参数', icon: 'none', duration: 1200 });
      }
    };

    const resetParams = () => resetAllParams(false);

    const syncFromStore = () => {
      const raw = photoStore.currentImagePath;
      if (!raw) return;
      if (raw === rawImagePath.value) return;
      rawImagePath.value = raw;
      originalPath.value = raw;
      baselinePath.value = raw;
      imagePath.value = toAppImageSrc(raw);
      baselinePath.value = raw;
      resetAllParams(true);
    };

    const applyAISuggestions = () => {
      const s = photoStore.editParams;
      if (!s || (s.brightness === 0 && !s.auto_enhance)) {
        uni.showToast({ title: '请先在首页进行 AI 分析', icon: 'none', duration: 2000 });
        return;
      }
      params.brightness = s.brightness || 0;
      params.contrast = s.contrast || 0;
      params.saturation = s.saturation || 0;
      params.sharpness = s.sharpness || 0;
      params.denoise = s.denoise || false;
      params.auto_enhance = true;
      activeModuleId.value = 'image';
      activeToolId.value = 'tone';
      toolScrollIntoView.value = 'tool-tone';
      aiHint.value = true;
      setTimeout(() => { aiHint.value = false; }, 2500);
    };

    const setCropRatio = (preset) => {
      cropRatioId.value = preset.id;
      cropAspectRatio.value = preset.ratio;
      if (preset.id === 'free') cropState.subMode = 'free';
      nextTick(() => cropOverlayRef.value?.resetCropBox?.());
    };

    const selectCropSubMode = (id) => {
      cropState.subMode = id;
      if (id === 'free') {
        cropRatioId.value = 'free';
        cropAspectRatio.value = null;
      }
    };

    const onRotateChanging = (e) => {
      cropState.rotate = e.detail.value;
    };
    const onRotateChange = (e) => {
      cropState.rotate = e.detail.value;
    };

    const toggleFlipH = () => {
      cropState.flipH = !cropState.flipH;
    };

    const rotate90Step = () => {
      cropState.rotate90 = (cropState.rotate90 + 1) % 4;
    };

    const restoreCropSession = () => {
      const snap = cropSessionSnapshot.value;
      if (!snap) {
        resetCropStateOnly();
        return;
      }
      rawImagePath.value = snap.path;
      originalPath.value = snap.path;
      imagePath.value = toAppImageSrc(snap.path);
      manualCropRect.value = { ...snap.cropRect };
      Object.assign(cropState, snap.cropState);
      cropRatioId.value = snap.cropRatioId;
      cropAspectRatio.value = snap.cropAspectRatio;
      showRatioPicker.value = false;
      uni.showToast({ title: '已还原', icon: 'none', duration: 800 });
    };

    const prepareCanvasSize = async (src) => {
      const info = await getImageInfo(src);
      canvasPx.value = { w: info.width, h: info.height };
      await nextTick();
      return info;
    };

    const bakeCropEdits = async () => {
      if (!rawImagePath.value || !hasPendingCropEdits()) {
        return rawImagePath.value;
      }
      cropProcessing.value = true;
      try {
        await prepareCanvasSize(rawImagePath.value);
        const newPath = await applyCropPipeline(
          rawImagePath.value,
          {
            flipH: cropState.flipH,
            flipV: cropState.flipV,
            rotateDeg: cropTotalRotate.value,
            cropNorm: manualCropRect.value,
          },
          vueInstance
        );
        rawImagePath.value = newPath;
        originalPath.value = newPath;
        imagePath.value = toAppImageSrc(newPath);
        photoStore.setCurrentImage(newPath);
        resetCropStateOnly();
        snapshotCropSession();
        return newPath;
      } finally {
        cropProcessing.value = false;
      }
    };

    const saveResult = async () => {
      if (!imagePath.value) {
        uni.showToast({ title: '请先上传图片', icon: 'none' });
        return;
      }
      if (saving.value) return;
      saving.value = true;

      try {
        if (hasPendingCropEdits()) {
          await bakeCropEdits();
        }

        const editOptions = {
          brightness: params.brightness,
          contrast: params.contrast,
          saturation: params.saturation,
          sharpness: params.sharpness,
          denoise: params.denoise,
          auto_enhance: params.auto_enhance,
          crop_x: 0, crop_y: 0, crop_w: 0, crop_h: 0,
        };

        const result = await photoAPI.editImage(originalPath.value, editOptions);
        const data = result.data;
        const url = data.proxy_url || data.output_full_url || data.output_url;

        imagePath.value = url;
        Object.assign(params, {
          brightness: 0, contrast: 0, saturation: 0,
          sharpness: 0, exposure: 0, temperature: 0,
          hue: 0, highlights: 0, shadows: 0, clarity: 0,
          mosaic: 0, blur_bg: 0,
          denoise: false, auto_enhance: false,
        });
        activeFilterId.value = 'none';

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
      const pages = getCurrentPages();
      if (pages.length > 1) {
        uni.navigateBack({ delta: 1 });
      } else {
        uni.switchTab({ url: '/pages/home/index' });
      }
    };

    const chooseImage = async () => {
      try {
        uni.showActionSheet({
          itemList: ['📷  拍照（原图）', '🖼  从相册选择（原图）'],
          success: async (res) => {
            try {
              let raw = '';
              if (res.tapIndex === 0) {
                const r = await photoAPI.takePhoto();
                raw = r.path || r;
              } else {
                raw = await photoAPI.chooseFromAlbum();
              }
              rawImagePath.value = raw;
              originalPath.value = raw;
              baselinePath.value = raw;
              imagePath.value = toAppImageSrc(raw);
              photoStore.setCurrentImage(raw);
              resetAllParams(true);
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
      syncFromStore();
      if (photoStore.currentImagePath) {
        baselinePath.value = photoStore.currentImagePath;
      }
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

    onShow(() => {
      syncFromStore();
    });

    return {
      imagePath,
      showOriginal,
      saving,
      aiHint,
      editorModules,
      activeModuleId,
      activeToolId,
      activeToolLabel,
      activeToolIcon,
      isPortraitTool,
      toolScrollIntoView,
      currentTools,
      filterPresets,
      activeFilterId,
      params,
      portraitParams,
      isCropMode,
      manualCropRect,
      cropAspectRatio,
      cropOverlayRef,
      cropProcessing,
      canvasPx,
      cropRatioPresets,
      cropRatioId,
      cropSubModes,
      cropState,
      cropTotalRotate,
      cropAngleDisplay,
      showRatioPicker,
      rulerTicks,
      previewStyle,
      topBarPaddingStyle,
      switchModule,
      selectTool,
      closeSubmenu,
      selectFilter,
      onComingSoon,
      resetParams,
      applyAISuggestions,
      setCropRatio,
      selectCropSubMode,
      onRotateChanging,
      onRotateChange,
      toggleFlipH,
      rotate90Step,
      restoreCropSession,
      saveResult,
      goBack,
      chooseImage,
    };
  },
};
</script>

<style lang="scss" scoped>
.editor-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #0a0a14;
  overflow: hidden;
}

.top-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 24rpx 16rpx;
  background: #131320;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  flex-shrink: 0;
  z-index: 10;
  box-sizing: border-box;
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
}

.no-img-icon { font-size: 80rpx; opacity: 0.25; }
.no-img-text { font-size: 28rpx; color: rgba(255,255,255,0.25); }

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

/* ── 底部面板 ── */
.bottom-panel {
  flex-shrink: 0;
  background: #131320;
  border-top: 1px solid rgba(255,255,255,0.07);
  padding-bottom: env(safe-area-inset-bottom);
  display: flex;
  flex-direction: column;
}

.bottom-expanded {
  max-height: 52vh;
}

/* 两大模块 */
.module-bar {
  display: flex;
  border-top: 1px solid rgba(255,255,255,0.06);
  flex-shrink: 0;
}

.module-tab {
  flex: 1;
  padding: 22rpx 0 18rpx;
  text-align: center;
  position: relative;
}

.module-tab::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 20%;
  right: 20%;
  height: 4rpx;
  border-radius: 4rpx;
  background: transparent;
  transition: background 0.2s;
}

.module-tab-active::after {
  background: linear-gradient(90deg, #7c6af5, #a855f7);
}

.module-tab-text {
  font-size: 28rpx;
  color: rgba(255,255,255,0.45);
  font-weight: 500;
}

.module-tab-active .module-tab-text {
  color: #c4b5fd;
  font-weight: 600;
}

/* 功能按钮横滑 */
.tools-scroll {
  width: 100%;
  white-space: nowrap;
  flex-shrink: 0;
  border-top: 1px solid rgba(255,255,255,0.04);
}

.tools-row {
  display: inline-flex;
  flex-direction: row;
  padding: 16rpx 12rpx 12rpx;
  gap: 12rpx;
}

.tool-btn {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  min-width: 108rpx;
  padding: 14rpx 20rpx;
  border-radius: 16rpx;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  flex-shrink: 0;
}

.tool-btn-active {
  background: rgba(124,106,245,0.18);
  border-color: rgba(124,106,245,0.55);
}

.tool-icon { font-size: 36rpx; line-height: 1; }
.tool-label {
  font-size: 22rpx;
  color: rgba(255,255,255,0.55);
  white-space: nowrap;
}

.tool-btn-active .tool-label {
  color: #c4b5fd;
  font-weight: 600;
}

/* 子菜单 */
.submenu-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid rgba(255,255,255,0.05);
  max-height: 36vh;
}

.submenu-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 24rpx 8rpx;
  flex-shrink: 0;
}

.submenu-title {
  font-size: 26rpx;
  color: #a78bfa;
  font-weight: 600;
}

.submenu-close {
  padding: 8rpx 20rpx;
  border-radius: 20rpx;
  background: rgba(255,255,255,0.06);
}

.submenu-close text {
  font-size: 22rpx;
  color: rgba(255,255,255,0.5);
}

.submenu-body {
  flex: 1;
  min-height: 0;
  max-height: 32vh;
}

.submenu-content {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  padding: 0 20rpx 16rpx;
}

.submenu-hint {
  font-size: 22rpx;
  color: rgba(255,255,255,0.35);
  line-height: 1.5;
  padding: 4rpx 0 8rpx;
}

/* 曲线占位 */
.curve-placeholder {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 20rpx 18rpx;
  background: rgba(255,255,255,0.03);
  border: 1px dashed rgba(124,106,245,0.35);
  border-radius: 14rpx;
}

.curve-icon { font-size: 28rpx; }
.curve-text { font-size: 24rpx; color: rgba(255,255,255,0.45); }

/* 滤镜 */
.filter-content { padding-bottom: 8rpx; }

.filter-scroll {
  width: 100%;
  white-space: nowrap;
}

.filter-row {
  display: inline-flex;
  gap: 16rpx;
  padding: 4rpx 8rpx 12rpx;
}

.filter-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  flex-shrink: 0;
}

.filter-thumb {
  width: 100rpx;
  height: 100rpx;
  border-radius: 12rpx;
  background: linear-gradient(135deg, #4a5568, #2d3748);
  border: 2px solid rgba(255,255,255,0.12);
}

.filter-chip-active .filter-thumb {
  border-color: #7c6af5;
  box-shadow: 0 0 12rpx rgba(124,106,245,0.5);
}

.filter-name {
  font-size: 22rpx;
  color: rgba(255,255,255,0.55);
}

.filter-chip-active .filter-name {
  color: #c4b5fd;
  font-weight: 600;
}

/* 开关 */
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

/* 手动裁切面板 */
.proc-canvas {
  position: fixed;
  left: -9999px;
  top: 0;
  opacity: 0;
  pointer-events: none;
}

.crop-panel {
  gap: 16rpx;
  padding-bottom: 8rpx;
}

.crop-quick-bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.crop-icon-btn {
  width: 72rpx;
  height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12rpx;
}

.crop-q-icon {
  font-size: 40rpx;
  color: rgba(255,255,255,0.85);
}

.crop-pill-btn {
  flex: 1;
  padding: 18rpx 0;
  text-align: center;
  background: rgba(255,255,255,0.08);
  border-radius: 40rpx;
}

.crop-pill-btn text {
  font-size: 28rpx;
  color: rgba(255,255,255,0.75);
}

.crop-pill-active {
  background: rgba(245,197,66,0.15);
}

.crop-pill-active text {
  color: #f5c542;
}

.ratio-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.ratio-chip {
  padding: 10rpx 22rpx;
  border-radius: 32rpx;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
}

.ratio-chip text {
  font-size: 24rpx;
  color: rgba(255,255,255,0.6);
}

.ratio-chip-active {
  background: rgba(245,197,66,0.2);
  border-color: #f5c542;
}

.ratio-chip-active text {
  color: #f5c542;
}

.crop-mode-scroll {
  width: 100%;
  white-space: nowrap;
}

.crop-mode-row {
  display: inline-flex;
  gap: 16rpx;
  padding: 4rpx 0;
}

.crop-mode-card {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 120rpx;
  height: 120rpx;
  border-radius: 16rpx;
  background: rgba(255,255,255,0.06);
  flex-shrink: 0;
  gap: 4rpx;
}

.crop-mode-active {
  background: rgba(245,197,66,0.22);
}

.crop-mode-icon {
  font-size: 32rpx;
  color: rgba(255,255,255,0.7);
}

.crop-mode-active .crop-mode-icon {
  color: #f5c542;
}

.crop-mode-label {
  font-size: 22rpx;
  color: rgba(255,255,255,0.5);
}

.crop-mode-active .crop-mode-label {
  color: #f5c542;
}

.crop-mode-value {
  font-size: 26rpx;
  font-weight: 700;
  color: #f5c542;
}

.rotate-ruler-wrap {
  padding: 8rpx 0 4rpx;
}

.ruler-scale {
  display: flex;
  justify-content: space-between;
  padding: 0 8rpx 8rpx;
}

.ruler-tick {
  font-size: 20rpx;
  color: rgba(255,255,255,0.35);
  width: 48rpx;
  text-align: center;
}

.rotate-slider {
  width: 100%;
}

.param-slider-wrap {
  padding-top: 4rpx;
}

.crop-free-hint {
  padding: 16rpx 0;
  text-align: center;
}

.crop-free-hint text {
  font-size: 24rpx;
  color: rgba(255,255,255,0.4);
}

/* 即将上线卡片 */
.action-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 36rpx 24rpx;
  background: rgba(124,106,245,0.08);
  border: 1px dashed rgba(124,106,245,0.35);
  border-radius: 16rpx;
}

.action-card-icon { font-size: 48rpx; }
.action-card-title { font-size: 28rpx; color: #c4b5fd; font-weight: 600; }
.action-card-desc {
  font-size: 22rpx;
  color: rgba(255,255,255,0.4);
  text-align: center;
}
</style>
