import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/** 浏览器侧 MSW worker（dev 接入；mockServiceWorker.js 位于 web/public/，经 `msw init` 生成）。 */
export const worker = setupWorker(...handlers);
