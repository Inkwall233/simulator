<script setup lang="ts">
const entries = [
  { title: '练习模式', desc: '由 Overlay 提供操作指引，学员自由练习设备操作。' },
  { title: '考试模式', desc: '按考试任务下发操作，Overlay 采集操作过程用于评分。' }
]
</script>

<template>
  <section class="page">
    <h1>考试前台</h1>
    <p class="lead">前台是承载仿真器的外壳，负责考试/练习入口、任务下发与成绩提交。</p>
    <ul class="cards">
      <li v-for="item in entries" :key="item.title" class="card">
        <h2>{{ item.title }}</h2>
        <p>{{ item.desc }}</p>
        <RouterLink class="card-link" to="/exam">进入仿真器 →</RouterLink>
      </li>
    </ul>
  </section>
</template>
