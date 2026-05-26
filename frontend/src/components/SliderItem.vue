<template>
  <view class="slider-item">
    <view class="slider-header">
      <view class="slider-left">
        <text class="slider-icon">{{ icon }}</text>
        <text class="slider-label">{{ label }}</text>
      </view>
      <view class="slider-right">
        <text class="slider-value" :class="valueClass">{{ displayValue }}</text>
        <view v-if="modelValue !== 0" class="reset-dot" @tap.stop="reset">
          <text class="reset-text">×</text>
        </view>
      </view>
    </view>
    <view class="slider-track">
      <text class="track-end">{{ min }}</text>
      <slider
        class="slider-ctrl"
        :value="modelValue"
        :min="min"
        :max="max"
        :step="1"
        activeColor="#7c6af5"
        backgroundColor="rgba(255,255,255,0.1)"
        block-color="#ffffff"
        :block-size="20"
        @change="onChange"
        @changing="onChanging"
      />
      <text class="track-end track-end-right">+{{ max }}</text>
    </view>
  </view>
</template>

<script>
import { computed } from 'vue';

export default {
  name: 'SliderItem',
  props: {
    label: { type: String, default: '' },
    icon: { type: String, default: '' },
    modelValue: { type: Number, default: 0 },
    min: { type: Number, default: -100 },
    max: { type: Number, default: 100 },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    const displayValue = computed(() => {
      const v = props.modelValue;
      return v > 0 ? `+${v}` : String(v);
    });

    const valueClass = computed(() => {
      if (props.modelValue > 0) return 'val-pos';
      if (props.modelValue < 0) return 'val-neg';
      return 'val-zero';
    });

    const onChange = (e) => {
      emit('update:modelValue', e.detail.value);
      emit('change', e.detail.value);
    };

    // changing 实时响应 → CSS filter 实时变化
    const onChanging = (e) => {
      emit('update:modelValue', e.detail.value);
    };

    const reset = () => {
      emit('update:modelValue', 0);
      emit('change', 0);
    };

    return { displayValue, valueClass, onChange, onChanging, reset };
  },
};
</script>

<style lang="scss" scoped>
.slider-item {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 14rpx;
  padding: 12rpx 18rpx 8rpx;
}

.slider-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4rpx;
}

.slider-left {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.slider-icon { font-size: 24rpx; }

.slider-label {
  font-size: 24rpx;
  color: rgba(255,255,255,0.75);
  font-weight: 500;
}

.slider-right {
  display: flex;
  align-items: center;
  gap: 10rpx;
}

.slider-value {
  font-size: 26rpx;
  font-weight: 700;
  min-width: 56rpx;
  text-align: right;
}

.val-pos { color: #a78bfa; }
.val-neg { color: #f472b6; }
.val-zero { color: rgba(255,255,255,0.3); }

.reset-dot {
  width: 32rpx;
  height: 32rpx;
  border-radius: 50%;
  background: rgba(255,255,255,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
}

.reset-text { font-size: 24rpx; color: rgba(255,255,255,0.5); line-height: 1; }

.slider-track {
  display: flex;
  align-items: center;
  gap: 8rpx;
}

.track-end {
  font-size: 18rpx;
  color: rgba(255,255,255,0.2);
  flex-shrink: 0;
  width: 44rpx;
}

.track-end-right { text-align: right; }

.slider-ctrl { flex: 1; }
</style>
