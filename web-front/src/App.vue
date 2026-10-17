<script setup lang="ts"></script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="brand">SkillLab 考试前台</span>
      <nav class="nav">
        <RouterLink to="/">考试列表</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
