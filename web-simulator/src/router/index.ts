import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue')
    },
    {
      path: '/runtime',
      name: 'runtime',
      component: () => import('@/views/RuntimeView.vue')
    }
  ]
})

export default router
