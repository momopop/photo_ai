import { defineStore } from 'pinia';
import { reactive, ref } from 'vue';

export const usePhotoStore = defineStore('photo', () => {
  // 当前处理的图片路径
  const currentImagePath      = ref('');
  const localOptimizedPath    = ref(''); // 端侧本地 AI 优化图（Tier 2）
  const currentImageSize = reactive({ width: 0, height: 0 });

  // 分析结果
  const analysisResult = reactive({
    detection: null,
    composition: null,
    loading: false,
    error: null,
  });

  // 修图参数
  const editParams = reactive({
    brightness: 0,
    contrast: 0,
    saturation: 0,
    sharpness: 0,
    denoise: false,
    auto_enhance: false,
    crop: null, // { x, y, width, height }
  });

  // 修图结果
  const editResult = reactive({
    outputUrl: '',
    loading: false,
    error: null,
    adjustmentsApplied: null,
  });

  // 自动构图结果
  const autoComposeResult = reactive({
    previewUrl: '',
    originalUrl: '',
    editSuggestions: null,
    loading: false,
    error: null,
  });

  // 历史记录
  const history = ref([]);

  function setCurrentImage(path, width = 0, height = 0, optimizedPath = '') {
    currentImagePath.value   = path;
    localOptimizedPath.value = optimizedPath;
    currentImageSize.width   = width;
    currentImageSize.height  = height;
    resetResults();
  }

  function resetResults() {
    analysisResult.detection = null;
    analysisResult.composition = null;
    analysisResult.loading = false;
    analysisResult.error = null;
    editResult.outputUrl = '';
    editResult.error = null;
    autoComposeResult.previewUrl = '';
    autoComposeResult.error = null;
    resetEditParams();
  }

  function resetEditParams() {
    editParams.brightness = 0;
    editParams.contrast = 0;
    editParams.saturation = 0;
    editParams.sharpness = 0;
    editParams.denoise = false;
    editParams.auto_enhance = false;
    editParams.crop = null;
  }

  function applySuggestions(suggestions) {
    if (!suggestions) return;
    editParams.brightness = suggestions.brightness || 0;
    editParams.contrast = suggestions.contrast || 0;
    editParams.saturation = suggestions.saturation || 0;
    editParams.sharpness = suggestions.sharpness || 0;
    editParams.denoise = suggestions.denoise || false;
    editParams.auto_enhance = suggestions.auto_enhance || false;
  }

  function applyCrop(crop) {
    editParams.crop = crop;
  }

  function addToHistory(item) {
    history.value.unshift({
      ...item,
      timestamp: Date.now(),
    });
    if (history.value.length > 20) {
      history.value.pop();
    }
  }

  return {
    currentImagePath,
    localOptimizedPath,
    currentImageSize,
    analysisResult,
    editParams,
    editResult,
    autoComposeResult,
    history,
    setCurrentImage,
    resetResults,
    resetEditParams,
    applySuggestions,
    applyCrop,
    addToHistory,
  };
});
