<script setup lang="ts"></script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="brand">SkillLab 仿真模拟器</span>
      <nav class="nav">
        <RouterLink to="/">概览</RouterLink>
        <RouterLink to="/runtime">仿真运行</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
