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
    },
    {
      path: '/simulators/:versionId',
      name: 'simulator-run',
      component: () => import('@/views/SimulatorRunView.vue')
    }
  ]
})

export default router
