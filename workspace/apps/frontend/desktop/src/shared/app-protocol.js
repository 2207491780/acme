import { protocol, net } from 'electron';
import { pathToFileURL } from 'node:url';
import { resolve, sep, extname } from 'node:path';

export const APP_SCHEME = 'acme';
export const APP_ORIGIN = 'acme://app';

export const schemePrivileges = [
  {
    scheme: APP_SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true },
  },
];

// ES module 与样式需要正确的 MIME，否则 Chromium 拒绝执行模块脚本。
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
};

export function registerAppProtocol(distRoot) {
  protocol.handle(APP_SCHEME, async (request) => {
    const { pathname } = new URL(request.url);
    let rel = decodeURIComponent(pathname);
    if (rel === '/' || rel === '') rel = '/index.html';
    if (rel.endsWith('/')) rel += 'index.html';
    const filePath = resolve(distRoot, `.${rel}`);
    if (filePath !== distRoot && !filePath.startsWith(distRoot + sep)) {
      return new Response('Forbidden', { status: 403 });
    }
    const res = await net.fetch(pathToFileURL(filePath).toString());
    const type = MIME[extname(filePath).toLowerCase()];
    if (!type) return res;
    const headers = new Headers(res.headers);
    headers.set('Content-Type', type);
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
  });
}