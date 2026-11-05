<script setup lang="ts"></script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="brand">SkillLab 管理端</span>
      <nav class="nav">
        <RouterLink to="/">概览</RouterLink>
        <RouterLink to="/simulator">仿真器</RouterLink>
        <RouterLink to="/ai-workbench">AI 制作</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
