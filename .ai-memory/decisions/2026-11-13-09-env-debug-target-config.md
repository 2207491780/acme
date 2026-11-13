# 环境上下文入配置：ai-coding-config.yml 增加 environment 段声明本地调试目标

> 扩展 `.ai-memory/decisions/2026-11-12-06-ai-coding-config.md`（engine 三开关不变，新增非引擎的环境上下文段）。
> 权威用法见 `.ai/ARCHITECTURE.md`「行为/引擎开关」；本记录定「为何环境声明放这里、引擎为何只校验不消费」。

## 背景

用户要求（2026-11-13）：环境相关内容应在 `ai-coding-config.yml` 中规定当前本地调试使用本地环境还是云端环境，并完善相关 AI 配置。
现状：前端 web 的 vite 代理经 `WEB_API_TARGET` 已支持两种模式（缺省 localhost:9888；云端联调 `http://acme.dev.local`），
但「当前用哪种」没有单一事实源，AI 工具调试前无从得知目标。用户确认当前为**云端 dev**。

## 决定

1. **新增顶层 `environment` 段**：`localDebug: local|cloud`（当前 `cloud`）+ `cloudEnv`（当前 `dev`，对应 `.ai/env/<env>.yaml`）。
   是现状声明而非引擎行为：AI 调试任务前读源文件并遵循；切换环境改此文件并重跑 sync。
2. **引擎只校验不消费**：`sync.mjs` 的 `CONFIG_DEFAULTS`/`validateConfig` 扩展接受该段（缺省 `local`/`dev` 向后兼容），
   严格校验沿用决策 06 姿态（未知键/非法枚举/错误类型写入前拒绝）；`cloudEnv` 只查非空字符串不做枚举，因 ENV-GUIDE 允许新增环境（如 staging）。
3. **规则联动（预算内）**：`rules/project.md` 环境行加短指针（RULES.md 拼接后仍 < 10240 字节预算）；
   frontend 分层规则加联调操作一句（cloud → `WEB_API_TARGET=http://acme.dev.local pnpm dev`）；ARCHITECTURE 登记 schema 与缺省。
4. **回归**：`.ai/tests/sync.test.mjs` 增 6 例（local/cloud/混合段接受 + 5 类非法拒绝），全套 66 例绿。

## 被否的选项

- **放 `.ai/env/`**：否决——该目录管 MCP 连接定义，不管调试目标声明；且用户明确指定 ai-coding-config.yml。
- **放 `engine.*` 下**：否决——engine 语义是「可选派生引擎行为」，环境是运行时上下文，混入会破坏决策 06 的边界。
- **引擎消费/派生该段**：否决——环境声明不参与派生，注入工具入口会污染 RULES 预算且无必要；AI 直接读源即可（仿 `.ai/env/`）。
- **`cloudEnv` 硬枚举 dev|test|prod**：否决——ENV-GUIDE 允许新增环境，硬枚举会迫使每次加环境改引擎。