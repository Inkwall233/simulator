<script setup lang="ts">
const simulatorUrl = import.meta.env.VITE_SIMULATOR_URL || 'http://localhost:5173'
</script>

<template>
  <section class="page page-fill">
    <h1>仿真器</h1>
    <iframe class="simulator-frame" :src="simulatorUrl" title="仿真器" />
  </section>
</template>
