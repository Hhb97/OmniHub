import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { setupDirectives } from './directives/permission'
import './styles/global.css'

const app = createApp(App)
app.use(createPinia())
setupDirectives(app)
app.use(router)
app.mount('#app')
