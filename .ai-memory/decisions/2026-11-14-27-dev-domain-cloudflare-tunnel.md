# dev 域名访问改走 Cloudflare Tunnel：备案未下期间的免备案公网入口

> 修订 ADR 2026-11-14-25（域名三环境分层）在 dev 环境的实际可达性：A 记录直连因备案未下被
> 腾讯云 80/443 拦截，本决策给 dev 换了不落境内 80/443 的公网链路；25 号决策的分层与 prod 终态不变。

## 背景

`acmecloud.site` NS 原在 DNSPod，`dev` A 记录直指服务器 124.223.118.235；备案未下，腾讯云按
Host 头拦截 80/443，浏览器侧打不开。集群内原有 cloudflared（本地配置型隧道，配置存集群
secret `cloudflared-config`，不入库）绑定的是免费域 `www.acmecloud.cc.cd`，该域 NS 无法正确
挂 Cloudflare（解析被污染），实际不可用。实测对照：CF 代理 A 记录直回源——HTTP 80 可通
（302）但 HTTPS 443 被丢包恒 522，且回源 80 随时可能被拦，不可依赖。

## 决定

- **zone `acmecloud.site` 迁 Cloudflare（Free）**：NS 换 `rene/april.ns.cloudflare.com`（已生效）。
  DNSPod 原 A 记录由 CF 导入保留（根域/test/www，已代理）——备案下来后即自然恢复为
  「CF 代理 + 免费 TLS」的直连入口，或按需切回直连。
- **dev 走隧道**：`dev` 删 A 改 CNAME → `2c68675e-914b-4d30-a8a2-57ef7338bc23.cfargotunnel.com`
  （代理开）。链路：公网 → CF 边缘（终结 TLS）→ 出站隧道 → `acme-web:80`；`/ws` 隧道侧正则
  `^/ws(/.*)?$` → `acme-gateway:9888`（与 k8s Ingress 语义一致，WebSocket 直连网关）。
- **隧道 ingress 配置留在集群 secret**（`kubectl edit secret cloudflared-config` 可见），不入库；
  本 ADR 记录其语义快照。cloudflared 为本地配置型（`--config` 启动），Dashboard 的 Public
  Hostname 表单对它无效——改路由必须改 secret 并滚动重启。
- **验证基线（2026-11-14）**：HTTPS 登录页 200；`/ws` 经隧道与网关直连行为一致（裸 GET 404，
  WebSocket 升级握手时走通）。

## 被否的选项

- **CF 代理 A 记录直回源**：443 回源被腾讯云丢包（恒 522），80 回源依赖拦截策略放行，不可靠。
- **trycloudflare quick tunnel**：URL 每次重启随机，不能作为固定入口。
- **继续用 acmecloud.cc.cd**：免费域 NS 不可控 + 解析污染，无法挂 Cloudflare。
- **等备案后再通**：dev 环境验证与演示被无限期阻塞；备案后仍可无缝切回直连（本方案不影响终态）。
