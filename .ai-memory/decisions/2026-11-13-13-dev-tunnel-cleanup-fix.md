# 修复 dev-tunnel 清理机制（pidfile + trap + pty），端到端连通实测通过

> 承接 `2026-11-13-12-dev-tunnel-restore.md`；**更正**：记录 12「保留原实测链路」就清理而言不成立——
> 原 down 的 `pkill -f` 与「Ctrl-C 连带清理」均实测失效，本次修复后重新实测。

## 背景

用户要求（2026-11-13）：测试 cloud-dev 隧道是否真能连上。以无凭证探针（PG SSLRequest / Redis PING /
Nacos readiness / 网关结构化错误）实测五端点全通；测试中暴露两个清理 bug：

1. **down 扫杀失效**：孤儿 kubectl 的 `/proc/PID/cmdline` 被置零（`kubectl\0\0…`），`pkill -f 'port-forward…'`
   永远匹配不到；且旧正则 `27[0-9]{3}` 只覆盖 27432/27379，漏掉 28848/29848/29888。
2. **断连不清理**：无 pty 的 SSH 会话断连时 sshd 不向命令进程组发 SIGHUP（实测远端 bash PPID=1 仍存活），
   后台 kubectl 孤儿化，占住服务器侧端口导致下次 up 立即失败。

## 决定

1. **up**：远端把 5 个 kubectl 的 `$!` 记入 `/tmp/acme-tunnel-<NS>.pids`，`trap 'kill …; rm -f …' HUP INT TERM EXIT`；
   SSH 加 **`-tt` 强制 pty**——断连时 pty 主端关闭，内核必发 SIGHUP，trap 得以触发（无 pty 则永不触发）。
2. **down**：弃用 `pkill -f`，改为 PID 文件 kill + `ss -ltnp` 按五个隧道端口扫杀（杀前校验 `/proc/PID/comm` = kubectl，
   共享主机防误杀），末尾复查监听清零，残留则 exit 1。
3. **验证方式**：`.ai-tmp/` 无凭证测试副本（剥离凭证打印块，其余与真脚本逐行一致）经 AI 工具实测：
   up 建链（PG 探针 "N"）→ 强杀本地 ssh 模拟断连 → 远端 pidfile/监听/kubectl 全清零；down 对真实残留扫杀通过。
   真脚本含凭证输出，仍由用户终端执行（纪律不变）。

## 被否的选项

- **远端轮询父 sshd 存活（watchdog）替代 pty**：否决——`-tt` 是标准机制且零额外代码；watchdog 留作未来备选。
- **down 继续用 pkill/ps 匹配命令行**：否决——argv 置零是内核行为（进程退出路径），匹配不可靠；按端口 + comm 校验更稳。
- **服务器 sshd 配 ClientAliveInterval 兜底**：否决——共享主机不改全局 sshd 配置；down 扫杀已兜底异常断连。