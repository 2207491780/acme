# 域名接入分层：acmecloud.site 三环境 host 互异，HTTPS 暂缓

> 来源：用户 2026-11-14 将域名 `acmecloud.site`（A 记录 → 124.223.118.235）指向 k3s 服务器，要求通过域名直接访问项目。

## 背景

- k3s 单机（决策 2026-11-13-06）：4G 内存同一时间只常驻一个环境，但 **Ingress 对象缩容后仍常驻集群**；Traefik 对多 namespace 同 host 的 Ingress 存在路由冲突（first-wins），各环境 host 必须互异。
- 三个 overlay 的 Ingress host 此前均为占位：dev=`acme.dev.local`（hosts 文件访问）、test=继承 base 的 `acme.local`、prod=`acme.example.com`（注释预留「域名上线时替换」）。
- 链路事实：web 的 nginx 同源反代网关（`/admin` `/app` → `acme-gateway:9888`，CSP `connect-src 'self'`），**换域名对前端/网关/API 零改动**；healthcheck.sh 动态读 ingress host，零改动。
- 用户两项决策：① 三环境分层（根域给 prod，dev/test 用子域）；② HTTPS 暂缓，本次只通 HTTP（443 安全组继续预留）。

## 决定

1. **host 分层**：prod=`acmecloud.site`（正式入口）；dev=`dev.acmecloud.site`；test=`test.acmecloud.site`。子域 A 记录由用户在 DNS 控制台补齐（同指向服务器 IP）；根域已生效。
2. **test overlay 补 `ingress-host.yaml` patch**（此前继承 base 占位 `acme.local`，从无真实访问入口）。
3. **prod CORS 白名单改 `http://acmecloud.site`**（同源反代下仅对非同源调用生效；TLS 上线后改 `https://`）。
4. **HTTPS 暂缓**：本次只通 80；证书方案（Traefik 内置 ACME 的 HelmChartConfig vs cert-manager，内存开销 vs 清单可入库管理）留待 TLS 任务另行决策。
5. 决策 2026-11-13-09 中 `WEB_API_TARGET=http://acme.dev.local` 示例随本决策过时（ADR 只增不改），以 `web/vite.config.ts` 注释与本记录为准。

## 被否的选项

- **dev 暂占根域 `acmecloud.site`**：否决——dev ingress 对象常驻，与 prod 上线后的根域 ingress 同 host 冲突，届时须回改 dev 配置，且环境语义混乱。
- **只改 prod、dev/test 维持占位**：否决（用户选定分层）——test 永远停留在无意义占位，dev 继续依赖 hosts 文件；分层后各环境访问方式一致，仅多两条子域 DNS 记录的一次性成本。
- **本次一起上 HTTPS（Traefik ACME 或 cert-manager）**：用户选择暂缓——先打通 HTTP 链路最快可用；TLS 涉及证书签发、HTTP 跳转与方案取舍，单独立任务避免本次改动面失控。
