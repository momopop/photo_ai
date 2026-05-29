<template>
  <view
    class="manual-crop-overlay"
    @touchmove.stop.prevent="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="onTouchEnd"
  >
    <view class="crop-stage" :id="stageId">
      <view class="transform-layer" :style="transformLayerStyle">
        <image
          class="crop-image"
          :src="imageSrc"
          mode="aspectFit"
          @load="onImageLoad"
        />

        <template v-if="layoutReady">
          <view class="shade shade-top" :style="shadeTop" />
          <view class="shade shade-bottom" :style="shadeBottom" />
          <view class="shade shade-left" :style="shadeLeft" />
          <view class="shade shade-right" :style="shadeRight" />

          <view
            class="crop-box"
            :style="boxStyle"
            @touchstart.stop="onTouchStart($event, 'move')"
          >
            <view class="crop-grid">
              <view class="grid-line grid-v1" />
              <view class="grid-line grid-v2" />
              <view class="grid-line grid-h1" />
              <view class="grid-line grid-h2" />
            </view>
            <view class="corner corner-tl" />
            <view class="corner corner-tr" />
            <view class="corner corner-bl" />
            <view class="corner corner-br" />
            <view
              v-for="h in HANDLES"
              :key="h"
              class="handle"
              :class="'handle-' + h"
              @touchstart.stop="onTouchStart($event, h)"
            />
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<script>
import { ref, computed, watch, nextTick, getCurrentInstance } from 'vue';
import { getImageInfo, calcAspectFitRect } from '../utils/imageCrop';

const HANDLES = ['tl', 'tr', 'bl', 'br'];

export const DEFAULT_CROP = () => ({ x: 0.08, y: 0.08, w: 0.84, h: 0.84 });

export default {
  name: 'ManualCropOverlay',
  props: {
    imageSrc: { type: String, default: '' },
    aspectRatio: { type: Number, default: null },
    rotation: { type: Number, default: 0 },
    flipH: { type: Boolean, default: false },
    flipV: { type: Boolean, default: false },
    skewH: { type: Number, default: 0 },
    skewV: { type: Number, default: 0 },
    modelValue: {
      type: Object,
      default: () => DEFAULT_CROP(),
    },
  },
  emits: ['update:modelValue'],
  setup(props, { emit, expose }) {
    const instance = getCurrentInstance();
    const stageId = `cropStage_${instance?.uid || Date.now()}`;

    const layoutReady = ref(false);
    const stageSize = ref({ w: 0, h: 0 });
    const imageRect = ref({ x: 0, y: 0, w: 0, h: 0 });
    const naturalSize = ref({ w: 0, h: 0 });
    const crop = ref({ ...props.modelValue });

    const touchMode = ref('');
    const touchStart = ref(null);
    const cropStart = ref(null);

    watch(
      () => props.modelValue,
      (v) => {
        if (v) crop.value = { ...v };
      },
      { deep: true }
    );

    const emitCrop = () => {
      emit('update:modelValue', { ...crop.value });
    };

    const transformLayerStyle = computed(() => {
      const sx = props.flipH ? -1 : 1;
      const sy = props.flipV ? -1 : 1;
      const parts = [
        'transform-origin: center center',
        `transform: rotate(${props.rotation}deg) scaleX(${sx}) scaleY(${sy}) skewX(${props.skewH}deg) skewY(${props.skewV}deg)`,
      ];
      return parts.join('; ');
    });

    const measureLayout = () => {
      const query = uni.createSelectorQuery().in(instance);
      query
        .select(`#${stageId}`)
        .boundingClientRect((stage) => {
          if (!stage || !naturalSize.value.w) return;
          stageSize.value = { w: stage.width, h: stage.height };
          imageRect.value = calcAspectFitRect(
            stage.width,
            stage.height,
            naturalSize.value.w,
            naturalSize.value.h
          );
          layoutReady.value = true;
        })
        .exec();
    };

    const onImageLoad = async () => {
      layoutReady.value = false;
      try {
        const info = await getImageInfo(props.imageSrc);
        naturalSize.value = { w: info.width, h: info.height };
        await nextTick();
        setTimeout(measureLayout, 50);
      } catch (e) {
        console.warn('[ManualCrop] getImageInfo failed', e);
      }
    };

    watch(() => props.imageSrc, () => {
      layoutReady.value = false;
      nextTick(() => onImageLoad());
    });

    const clampCrop = (c) => {
      const min = 0.08;
      let { x, y, w, h } = c;
      w = Math.max(min, Math.min(1, w));
      h = Math.max(min, Math.min(1, h));
      x = Math.max(0, Math.min(1 - w, x));
      y = Math.max(0, Math.min(1 - h, y));
      return { x, y, w, h };
    };

    const fitAspect = (c, anchor) => {
      const ratio = props.aspectRatio;
      if (!ratio || ratio <= 0) return c;
      const ir = imageRect.value;
      let { x, y, w, h } = c;
      const boxRatio = (w * ir.w) / (h * ir.h);
      if (boxRatio > ratio) {
        w = (h * ir.h * ratio) / ir.w;
      } else {
        h = (w * ir.w) / (ratio * ir.h);
      }
      if (anchor === 'tr' || anchor === 'br') x = c.x + c.w - w;
      if (anchor === 'bl' || anchor === 'br') y = c.y + c.h - h;
      return clampCrop({ x, y, w, h });
    };

    const fitAspectDefault = (ratio) => {
      const ir = imageRect.value;
      if (!ir.w || !ir.h) return DEFAULT_CROP();
      const displayRatio = ir.w / ir.h;
      let w = 0.84;
      let h = 0.84;
      if (displayRatio > ratio) {
        h = 0.84;
        w = (h * ir.h * ratio) / ir.w;
      } else {
        w = 0.84;
        h = (w * ir.w) / (ratio * ir.h);
      }
      return clampCrop({ x: (1 - w) / 2, y: (1 - h) / 2, w, h });
    };

    const boxStyle = computed(() => {
      const ir = imageRect.value;
      const c = crop.value;
      return {
        left: `${ir.x + c.x * ir.w}px`,
        top: `${ir.y + c.y * ir.h}px`,
        width: `${c.w * ir.w}px`,
        height: `${c.h * ir.h}px`,
      };
    });

    const boxPx = computed(() => {
      const ir = imageRect.value;
      const c = crop.value;
      return {
        left: ir.x + c.x * ir.w,
        top: ir.y + c.y * ir.h,
        width: c.w * ir.w,
        height: c.h * ir.h,
      };
    });

    const shadeTop = computed(() => ({
      left: 0,
      top: 0,
      width: '100%',
      height: `${Math.max(0, boxPx.value.top)}px`,
    }));
    const shadeBottom = computed(() => ({
      left: 0,
      top: `${boxPx.value.top + boxPx.value.height}px`,
      right: 0,
      bottom: 0,
    }));
    const shadeLeft = computed(() => ({
      left: 0,
      top: `${boxPx.value.top}px`,
      width: `${Math.max(0, boxPx.value.left)}px`,
      height: `${boxPx.value.height}px`,
    }));
    const shadeRight = computed(() => ({
      left: `${boxPx.value.left + boxPx.value.width}px`,
      top: `${boxPx.value.top}px`,
      right: 0,
      height: `${boxPx.value.height}px`,
    }));

    const onTouchStart = (e, mode) => {
      if (!layoutReady.value) return;
      const t = e.touches[0];
      touchMode.value = mode;
      touchStart.value = { x: t.clientX, y: t.clientY };
      cropStart.value = { ...crop.value };
    };

    const onTouchMove = (e) => {
      if (!touchStart.value || !cropStart.value || !touchMode.value) return;
      const t = e.touches[0];
      const ir = imageRect.value;
      if (!ir.w || !ir.h) return;

      const dx = (t.clientX - touchStart.value.x) / ir.w;
      const dy = (t.clientY - touchStart.value.y) / ir.h;
      const mode = touchMode.value;
      const s = cropStart.value;
      let next = { ...s };

      if (mode === 'move') {
        next.x = s.x + dx;
        next.y = s.y + dy;
      } else {
        if (mode.includes('l')) {
          next.x = s.x + dx;
          next.w = s.w - dx;
        }
        if (mode.includes('r')) next.w = s.w + dx;
        if (mode.includes('t')) {
          next.y = s.y + dy;
          next.h = s.h - dy;
        }
        if (mode.includes('b')) next.h = s.h + dy;
      }

      next = clampCrop(next);
      if (props.aspectRatio && mode !== 'move') {
        next = fitAspect(next, mode);
      }
      crop.value = next;
      emitCrop();
    };

    const onTouchEnd = () => {
      touchMode.value = '';
      touchStart.value = null;
      cropStart.value = null;
    };

    const resetCropBox = () => {
      crop.value = props.aspectRatio
        ? fitAspectDefault(props.aspectRatio)
        : DEFAULT_CROP();
      emitCrop();
    };

    watch(
      () => props.aspectRatio,
      (ratio) => {
        if (ratio && layoutReady.value) {
          crop.value = fitAspectDefault(ratio);
          emitCrop();
        }
      }
    );

    expose({ resetCropBox, remeasure: measureLayout });

    return {
      HANDLES,
      stageId,
      layoutReady,
      transformLayerStyle,
      boxStyle,
      shadeTop,
      shadeBottom,
      shadeLeft,
      shadeRight,
      onImageLoad,
      onTouchStart,
      onTouchMove,
      onTouchEnd,
    };
  },
};
</script>

<style lang="scss" scoped>
.manual-crop-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  overflow: hidden;
}

.crop-stage {
  width: 100%;
  height: 100%;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}

.transform-layer {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.crop-image {
  width: 100%;
  height: 100%;
}

.shade {
  position: absolute;
  background: rgba(0, 0, 0, 0.55);
  pointer-events: none;
  z-index: 6;
}

.crop-box {
  position: absolute;
  box-sizing: border-box;
  border: 1px solid rgba(255, 255, 255, 0.85);
  z-index: 8;
}

.crop-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.grid-line {
  position: absolute;
  background: rgba(255, 255, 255, 0.35);
}

.grid-v1 { left: 33.33%; top: 0; bottom: 0; width: 1px; }
.grid-v2 { left: 66.66%; top: 0; bottom: 0; width: 1px; }
.grid-h1 { top: 33.33%; left: 0; right: 0; height: 1px; }
.grid-h2 { top: 66.66%; left: 0; right: 0; height: 1px; }

.corner {
  position: absolute;
  width: 28rpx;
  height: 28rpx;
  border-color: #111;
  border-style: solid;
  z-index: 9;
  pointer-events: none;
}

.corner-tl { left: -2px; top: -2px; border-width: 6rpx 0 0 6rpx; }
.corner-tr { right: -2px; top: -2px; border-width: 6rpx 6rpx 0 0; }
.corner-bl { left: -2px; bottom: -2px; border-width: 0 0 6rpx 6rpx; }
.corner-br { right: -2px; bottom: -2px; border-width: 0 6rpx 6rpx 0; }

.handle {
  position: absolute;
  width: 36rpx;
  height: 36rpx;
  z-index: 10;
  background: transparent;
}

.handle-tl { left: -18rpx; top: -18rpx; }
.handle-tr { right: -18rpx; top: -18rpx; }
.handle-bl { left: -18rpx; bottom: -18rpx; }
.handle-br { right: -18rpx; bottom: -18rpx; }
</style>
