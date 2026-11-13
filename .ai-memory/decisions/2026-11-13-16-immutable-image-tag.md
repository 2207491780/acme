# 镜像 tag 契约为不可变 <env>-<sha7>（取代 07 的 <env>-latest）

> 取代 `2026-11-13-07-ci-github-dual-hosting.md` 中「tag `<env>-latest`」的结论；
> 07 的平台决策（GitHub Actions 双托管）、CD 链路（scp + SSH 触发）与 Secrets 体系不变。

## 背景

07 落地后部署两次空跑（2026-11-13 实测）：可变 `<env>-latest` tag 下 k8s spec 未变不滚动 + 节点缓存旧镜像，
CI 全绿而代码不达服务器。修复期间还发生误截「dev-<sha>」整串只剩 3 位 SHA 的事故（碰撞空间过小）。
conversational-cd.md 与 cd.yml 注释已按不可变 tag 口径书写，但决策链仍载 07 的 `-latest` 为有效结论——
与 ADR 14 闭环的「改事实不改决策链」同类缺口，本记录补齐。

## 决定

1. **CI 只推不可变 tag**：`<env>-<sha7>`，sha7 截取 GITHUB_SHA 本体；build 与 deploy 同 tag，
   deploy.sh 经 `ACME_IMAGE_TAG` 环境变量注入（最高优先级）。
2. **回滚语义**：以旧 commit 触发 dispatch（tag 指向旧镜像即回滚），或服务器 `kubectl rollout undo`。
3. **deploy.sh 的 `<env>-latest` 兜底仅属手动路径**：CI 不再推送该 tag；手动部署应显式指定
   ACME_IMAGE_TAG 或 registry.env，不依赖兜底。

## 被否的选项

- **保留 <env>-latest 可变 tag**：否决——k8s spec 未变不滚动 + 节点镜像缓存，CI 绿而代码不达（两次实测空跑）。
- **对「dev-<sha>」整串截取短 SHA**：否决——只剩 3 位 SHA，碰撞空间 12 位过小；截取对象必须是
  GITHUB_SHA 本体（实测事故）。