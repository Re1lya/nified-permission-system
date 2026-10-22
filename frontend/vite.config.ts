import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';
export default defineConfig({base:'/ui/',plugins:[vue(),tailwindcss()],build:{outDir:'../src/main/resources/static/ui',emptyOutDir:true},server:{proxy:{'/api':'http://127.0.0.1:18081'}}});
