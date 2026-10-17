<script setup lang="ts">
const capabilities = [
  {
    title: '接口 Mock（Service Worker）',
    desc: '拦截设备静态包的接口请求，返回有状态的模拟响应（登录态、设备配置、录像列表等）。'
  },
  {
    title: 'Overlay 覆盖层',
    desc: '在设备页面上叠加练习指引与考试操作，并可注入跳过登录等交互。'
  },
  {
    title: '插件劫持',
    desc: '替换设备原有插件式能力（如录像回放）为视频转图片帧的模拟播放。'
  }
]
</script>

<template>
  <section class="page">
    <h1>仿真模拟器</h1>
    <p class="lead">
      将设备（摄像机 / NVR 等）Web 操作页面的静态构建产物加工为可仿真的运行环境。
    </p>
    <ul class="cards">
      <li v-for="item in capabilities" :key="item.title" class="card">
        <h2>{{ item.title }}</h2>
        <p>{{ item.desc }}</p>
      </li>
    </ul>
  </section>
</template>
