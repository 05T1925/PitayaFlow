import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({root:'apps/web',plugins:[vue()],server:{host:'127.0.0.1',port:5173,proxy:{'/api':{target:process.env.PF_WEB_API_URL||'http://127.0.0.1:18080',changeOrigin:false}}},build:{outDir:'../../dist/web',emptyOutDir:true},test:{environment:'jsdom',include:['src/**/*.test.js']}});
