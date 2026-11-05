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
      path: '/simulator',
      name: 'simulator',
      component: () => import('@/views/SimulatorView.vue')
    },
    {
      path: '/ai-workbench',
      name: 'ai-workbench',
      component: () => import('@/views/AiWorkbenchView.vue')
    }
  ]
})

export default router
