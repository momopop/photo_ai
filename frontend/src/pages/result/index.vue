<template>
  <view class="result-page">

    <!-- 状态栏占位 -->
    <view :style="{ height: statusBarH + 'px', background: '#000' }"></view>

    <!-- 顶部栏 -->
    <view class="top-bar">
      <view class="btn-back" @tap="goBack">
        <text class="back-icon">‹</text>
      </view>
      <text class="page-title">拍摄结果</text>
      <view style="width:60rpx"></view>
    </view>

    <!-- 仅相册图需云端优化时显示此横幅 -->
    <view v-if="aiLoading" class="loading-banner">
      <view class="banner-dots">
        <view class="bdot" v-for="i in 3" :key="i" :style="{ animationDelay: (i-1)*0.25+'s' }"></view>
      </view>
      <text class="banner-text">正在优化构图...</text>
    </view>

    <!-- ══ 图片横向滑动区 ══ -->
    <scroll-view
      class="image-strip"
      scroll-x
      :scroll-left="stripScrollLeft"
      :scroll-with-animation="true"
      @scroll="onStripScroll"
      :style="{ height: imageAreaH+'px', width: windowW+'px' }"
    >
      <view class="strip-inner" :style="{ width: totalStripW+'px', height: imageAreaH+'px' }">

        <!-- 卡片 0：原图 -->
        <view class="img-card" :style="cardStyle">
          <image v-if="originalPath" class="card-img" :src="originalPath" mode="aspectFit" />
          <view v-else class="card-placeholder">
            <text class="placeholder-text">图片加载中...</text>
          </view>
          <view class="card-label-wrap">
            <text class="card-label">原图</text>
          </view>
        </view>

        <!-- 卡片 1：本地 AI 优化（Tier 2，离线） -->
        <view class="img-card" :style="cardStyle">
          <view v-if="aiLoading" class="card-placeholder">
            <view class="placeholder-ring"></view>
            <text class="placeholder-text">AI 优化中...</text>
          </view>
          <template v-else>
            <image v-if="optimizedPath" class="card-img" :src="optimizedPath" mode="aspectFit" @error="onOptimizedError" />
            <view v-else class="card-placeholder">
              <text class="placeholder-icon">⚠️</text>
              <text class="placeholder-text">优化未成功</text>
              <text class="placeholder-sub">可保存原图，或点击 AI 精修</text>
            </view>
          </template>
          <view class="card-label-wrap ai-label-wrap">
            <text class="card-label ai-label-text">
              {{ fromCamera ? '本地 AI 优化' : 'AI 优化' }}
            </text>
          </view>
        </view>

        <!-- 卡片 2：云端 AI 精修（Tier 3，用户触发） -->
        <view v-if="showRefineCard" class="img-card" :style="cardStyle">
          <view v-if="refineLoading" class="card-placeholder">
            <view class="placeholder-ring"></view>
            <text class="placeholder-text">云端精修中...</text>
          </view>
          <template v-else>
            <image
              v-if="refinedPath"
              class="card-img"
              :src="refinedPath"
              mode="aspectFit"
              @error="onRefinedError"
            />
            <view v-else class="card-placeholder">
              <text class="placeholder-text">精修失败</text>
            </view>
          </template>
          <view class="card-label-wrap refine-label-wrap">
            <text class="card-label refine-label-text">云端 AI 精修</text>
          </view>
        </view>

      </view>
    </scroll-view>

    <!-- 分页指示点 -->
    <view class="dots-row">
      <view class="dot" :class="{ 'dot-active': currentCard===0 }" @tap="scrollToCard(0)"></view>
      <view class="dot" :class="{ 'dot-active': currentCard===1 }" @tap="scrollToCard(1)"></view>
      <view v-if="showRefineCard" class="dot" :class="{ 'dot-active': currentCard===2 }" @tap="scrollToCard(2)"></view>
    </view>

    <!-- 底部操作栏 -->
    <view class="action-bar">
      <!-- 保存：保存当前可见卡片的图片 -->
      <view class="action-btn save-btn" @tap="handleSave" :class="{ 'btn-disabled': saving }">
        <text class="action-icon">💾</text>
        <text class="action-text">{{ saving ? '保存中...' : '保存' }}</text>
      </view>

      <!-- AI 精修：触发云端（Tier 3） -->
      <view
        class="action-btn refine-btn"
        @tap="handleRefine"
        :class="{ 'btn-disabled': refineLoading || aiLoading }"
      >
        <text class="action-icon">✨</text>
        <text class="action-text">AI 精修</text>
      </view>

      <!-- 编辑：跳修图页，传入本地优化图 -->
      <view class="action-btn edit-btn" @tap="handleEdit">
        <text class="action-icon">🎨</text>
        <text class="action-text">编辑</text>
      </view>
    </view>

  </view>
</template>

<script>
import { ref, computed, onMounted } from 'vue';
import { usePhotoStore } from '../../store/photo';
import { photoAPI, toAppImageSrc, resolveServerImageUrl } from '../../api/photoai';

export default {
  name: 'ResultPage',
  setup() {
    const photoStore = usePhotoStore();

    const statusBarH  = ref(0);
    const windowW     = ref(375);
    const windowH     = ref(667);
    const imageAreaH  = ref(400);

    const rawOriginalPath  = computed(() => photoStore.currentImagePath);
    const rawOptimizedPath = ref('');
    const rawRefinedPath   = ref('');
    const originalPath   = computed(() => toAppImageSrc(rawOriginalPath.value));
    const optimizedPath  = computed(() => toAppImageSrc(rawOptimizedPath.value));
    const refinedPath    = computed(() => toAppImageSrc(rawRefinedPath.value));
    const fromCamera     = ref(false); // 标记是否来自相机（有本地优化）

    const aiLoading     = ref(false);
    const refineLoading = ref(false);
    const saving        = ref(false);
    const showRefineCard  = ref(false);
    const currentCard     = ref(0);
    const stripScrollLeft = ref(0);

    const cardStyle = computed(() => ({
      width:  windowW.value + 'px',
      height: imageAreaH.value + 'px',
    }));
    const cardCount   = computed(() => showRefineCard.value ? 3 : 2);
    const totalStripW = computed(() => cardCount.value * windowW.value);

    const scrollToCard = (idx) => {
      currentCard.value     = idx;
      stripScrollLeft.value = idx * windowW.value;
    };
    const onStripScroll = (e) => {
      currentCard.value = Math.round((e.detail?.scrollLeft ?? 0) / windowW.value);
    };

    // 当前激活路径（保存/上传用原始路径；展示用 toAppImageSrc）
    const activeRawPath = computed(() => {
      if (currentCard.value === 2 && rawRefinedPath.value)   return rawRefinedPath.value;
      if (currentCard.value === 1 && rawOptimizedPath.value) return rawOptimizedPath.value;
      return rawOriginalPath.value;
    });

    // ── Tier 2：读取本地优化图（相机拍摄时已在端上完成，无需请求） ─────────
    const initOptimized = async () => {
      const localOpt = photoStore.localOptimizedPath;
      if (localOpt) {
        // 相机拍照：本地 AI 已在 CameraActivity 内完成
        fromCamera.value     = true;
        rawOptimizedPath.value = localOpt;
        aiLoading.value      = false;
        // 自动展示 AI 优化卡片
        setTimeout(() => scrollToCard(1), 200);
      } else {
        // 相册选图：调云端 auto-compose 做优化
        fromCamera.value    = false;
        aiLoading.value     = true;
        try {
          const res  = await photoAPI.quickOptimize(rawOriginalPath.value);
          const data = res.data || res;
          const url  = resolveServerImageUrl(data);
          if (url) {
            rawOptimizedPath.value = url;
            setTimeout(() => scrollToCard(1), 200);
          }
        } catch (err) {
          console.warn('[Result] 云端优化失败:', err.message);
        } finally {
          aiLoading.value = false;
        }
      }
    };

    // ── Tier 3：云端 AI 精修（用户主动点击） ─────────────────────────────────
    const handleRefine = async () => {
      if (refineLoading.value || aiLoading.value) return;
      showRefineCard.value = true;
      refineLoading.value  = true;
      setTimeout(() => scrollToCard(2), 100);
      try {
        // 上传须用本地路径；云端优化图为 http 时回退原图
        const opt = rawOptimizedPath.value;
        const src = (opt && !/^https?:\/\//i.test(opt))
          ? opt
          : rawOriginalPath.value;
        const res  = await photoAPI.refinedOptimize(src);
        const data = res.data || res;
        const url  = resolveServerImageUrl(data);
        if (url) {
          rawRefinedPath.value = url;
        } else {
          showRefineCard.value = false;
          scrollToCard(1);
          uni.showToast({ title: 'AI精修未返回图片', icon: 'none' });
        }
      } catch (err) {
        showRefineCard.value = false;
        scrollToCard(1);
        uni.showToast({ title: '精修失败: ' + err.message, icon: 'none', duration: 2500 });
      } finally {
        refineLoading.value = false;
      }
    };

    const onOptimizedError = () => {
      rawOptimizedPath.value = '';
    };

    const onRefinedError = () => {
      rawRefinedPath.value = '';
      uni.showToast({ title: '精修图加载失败', icon: 'none' });
    };

    // ── 保存：保存当前可见图片，完成后返回拍照界面 ──────────────────────────
    const handleSave = async () => {
      if (saving.value) return;
      const path = activeRawPath.value;
      if (!path) { uni.showToast({ title: '暂无可保存的图片', icon: 'none' }); return; }
      saving.value = true;
      try {
        await photoAPI.saveToAlbum(path);
        uni.showToast({ title: '已保存到相册 ✓', icon: 'success', duration: 1200 });
      } catch (err) {
        uni.showToast({ title: err.message || '保存失败', icon: 'none' });
      } finally {
        saving.value = false;
      }
    };

    // ── 编辑：传入当前结果页展示的那张图（滑到哪张传哪张） ──────────────────
    const handleEdit = () => {
      const displayPath = activeRawPath.value;
      if (!displayPath) {
        uni.showToast({ title: '暂无可编辑的图片', icon: 'none' });
        return;
      }
      // store 里的 currentImagePath 决定修图页展示什么
      photoStore.setCurrentImage(displayPath, 0, 0, '');
      uni.navigateTo({
        url: '/pages/editor/index',
        animationType: 'slide-in-right',
        fail: () => {
          uni.showToast({ title: '无法打开修图页', icon: 'none' });
        },
      });
    };

    // 返回拍照界面（navigateBack 回到 home/camera）
    const goBack = () => { uni.navigateBack({ delta: 1 }); };

    onMounted(() => {
      const info = uni.getSystemInfoSync();
      statusBarH.value = info.statusBarHeight || 0;
      windowW.value    = info.windowWidth     || 375;
      windowH.value    = info.windowHeight    || 667;

      const rpx        = windowW.value / 750;
      const topBarPx   = Math.round(88  * rpx);
      const dotsPx     = Math.round(44  * rpx);
      const actionPx   = Math.round(180 * rpx);
      imageAreaH.value = Math.max(200,
        windowH.value - statusBarH.value - topBarPx - dotsPx - actionPx);

      currentCard.value     = 0;
      stripScrollLeft.value = 0;
      initOptimized();
    });

    return {
      statusBarH, windowW, imageAreaH, totalStripW,
      originalPath, optimizedPath, refinedPath,
      fromCamera,
      aiLoading, refineLoading, saving,
      showRefineCard, currentCard, stripScrollLeft,
      cardStyle,
      scrollToCard, onStripScroll,
      onOptimizedError,
      onRefinedError,
      handleRefine, handleSave, handleEdit, goBack,
    };
  },
};
</script>

<style lang="scss" scoped>
page { height: 100%; overflow: hidden; background: #000; }

.result-page {
  display: flex;
  flex-direction: column;
  background: #0a0a12;
  overflow: hidden;
}

/* ── 顶部栏 ── */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 88rpx;
  padding: 0 12rpx 0 4rpx;
  background: rgba(0,0,0,.75);
  flex-shrink: 0;
}
.btn-back { width:72rpx; height:72rpx; display:flex; align-items:center; justify-content:center; }
.back-icon { font-size:60rpx; color:#fff; line-height:1; margin-top:-6rpx; }
.page-title { font-size:32rpx; font-weight:700; color:#fff; letter-spacing:1rpx; }

/* ── 云端优化横幅 ── */
.loading-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16rpx;
  height: 64rpx;
  background: rgba(124,106,245,.18);
  border-bottom: 1px solid rgba(124,106,245,.3);
  flex-shrink: 0;
}
.banner-dots { display:flex; gap:8rpx; align-items:center; }
.bdot {
  width:8rpx; height:8rpx; border-radius:50%;
  background:#a78bfa;
  animation: bounce .9s ease-in-out infinite;
}
@keyframes bounce { 0%,100%{transform:translateY(0);opacity:.5} 50%{transform:translateY(-6rpx);opacity:1} }
.banner-text { font-size:26rpx; color:#c4b5fd; letter-spacing:1rpx; }

/* ── 图片横向滑动带 ── */
.image-strip { flex-shrink:0; overflow:hidden; background:#000; }
.strip-inner { display:flex; flex-direction:row; }

.img-card {
  position: relative;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
  overflow: hidden;
}
.card-img { width:100%; height:100%; }

/* 占位 */
.card-placeholder {
  display: flex; flex-direction:column; align-items:center; justify-content:center;
  gap:20rpx; width:100%; height:100%;
}
.placeholder-ring {
  width:72rpx; height:72rpx;
  border:4rpx solid rgba(255,255,255,.12);
  border-top-color:#a78bfa;
  border-radius:50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to{transform:rotate(360deg)} }
.placeholder-icon { font-size:60rpx; }
.placeholder-text { font-size:28rpx; color:rgba(255,255,255,.5); }
.placeholder-sub  { font-size:22rpx; color:rgba(255,255,255,.3); }

/* 图片标签 */
.card-label-wrap {
  position:absolute; bottom:0; left:0; right:0;
  padding:16rpx 24rpx;
  background:linear-gradient(transparent,rgba(0,0,0,.65));
}
.card-label       { font-size:24rpx; color:rgba(255,255,255,.75); font-weight:500; }
.ai-label-wrap    { background:linear-gradient(transparent,rgba(60,40,120,.70)); }
.ai-label-text    { color:#c4b5fd; }
.refine-label-wrap { background:linear-gradient(transparent,rgba(20,80,80,.70)); }
.refine-label-text  { color:#6ee7b7; }

/* ── 分页点 ── */
.dots-row {
  display:flex; justify-content:center; align-items:center;
  gap:16rpx; height:44rpx; flex-shrink:0; background:#0a0a12;
}
.dot { width:12rpx; height:12rpx; border-radius:6rpx; background:rgba(255,255,255,.2); transition:all .25s ease; }
.dot-active { width:32rpx; background:#a78bfa; }

/* ── 底部操作栏 ── */
.action-bar {
  display:flex; align-items:center; gap:20rpx;
  padding:20rpx 30rpx 32rpx;
  background:rgba(0,0,0,.9);
  border-top:1px solid rgba(255,255,255,.06);
  flex-shrink:0;
}
.action-btn {
  flex:1; display:flex; flex-direction:column;
  align-items:center; gap:10rpx; padding:22rpx 10rpx;
  border-radius:20rpx; transition:opacity .15s;
}
.action-btn:active { opacity:.7; }
.action-icon { font-size:44rpx; }
.action-text { font-size:24rpx; font-weight:600; color:#fff; }

.save-btn {
  background:linear-gradient(135deg,#7c6af5,#a855f7);
  box-shadow:0 6rpx 24rpx rgba(124,106,245,.45);
}
.refine-btn {
  background:rgba(124,106,245,.15);
  border:1px solid rgba(124,106,245,.4);
}
.edit-btn {
  background:rgba(255,255,255,.07);
  border:1px solid rgba(255,255,255,.12);
}
.btn-disabled { opacity:.4; pointer-events:none; }
</style>
