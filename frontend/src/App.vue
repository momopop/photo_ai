<script>
export default {
  onLaunch() {
    console.log('PhotoAI App launched');
  },
  onShow() {},
  onHide() {},
};
</script>

<style lang="scss">
/* 全局样式重置 */
page {
  background-color: #0f0f1a;
  color: #ffffff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

page, view {
  box-sizing: border-box;
}

/* 通用工具类 */
.flex { display: flex; }
.flex-col { flex-direction: column; }
.items-center { align-items: center; }
.justify-center { justify-content: center; }
.justify-between { justify-content: space-between; }
.text-center { text-align: center; }
.w-full { width: 100%; }
.h-full { height: 100%; }
.relative { position: relative; }
.absolute { position: absolute; }

/* 渐变主题色 */
.gradient-primary {
  background: linear-gradient(135deg, #7c6af5 0%, #a855f7 50%, #ec4899 100%);
}

/* 卡片样式 */
.card {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16rpx;
  padding: 24rpx;
}

/* 主按钮 */
.btn-primary {
  background: linear-gradient(135deg, #7c6af5, #a855f7);
  color: white;
  border-radius: 50rpx;
  padding: 24rpx 48rpx;
  font-size: 30rpx;
  font-weight: 600;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
}

.btn-primary:active {
  opacity: 0.85;
  transform: scale(0.98);
}

/* 次级按钮 */
.btn-secondary {
  background: rgba(255, 255, 255, 0.1);
  color: white;
  border-radius: 50rpx;
  padding: 24rpx 48rpx;
  font-size: 28rpx;
  border: 1px solid rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
