<script setup lang="ts">
const modules = [
  { title: '设备包管理', desc: '上传与维护设备 Web 静态包及其加工配置。' },
  { title: '考试与题库', desc: '编排考试任务、操作步骤与评分规则。' },
  { title: '用户与权限', desc: '管理学员、管理员账号与角色权限。' },
  { title: '仿真包分发', desc: '将加工后的仿真包下发给前台与仿真器。' }
]
</script>

<template>
  <section class="page">
    <h1>管理端概览</h1>
    <p class="lead">管理端是承载仿真器的外壳，业务逻辑由后端 SkillLab-Admin 提供。</p>
    <ul class="cards">
      <li v-for="item in modules" :key="item.title" class="card">
        <h2>{{ item.title }}</h2>
        <p>{{ item.desc }}</p>
      </li>
    </ul>
  </section>
</template>
