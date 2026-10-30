# 项目上下文

> 所有 AI 工具共享的项目背景说明。标注 TODO 的部分请在项目定型后补齐。

## 项目简介

TODO：一句话说清楚本项目解决什么问题、给谁用。

## 技术栈

| 层 | 选型 | 版本 | 备注 |
|---|---|---|---|
| 语言 | TODO | | |
| 框架 | TODO | | |
| 包管理 | TODO | | npm / pnpm / uv / cargo … |
| 测试 | TODO | | |
| 构建 / 运行 | TODO | | |

## 目录结构

TODO：项目骨架确定后在此登记，AI 会优先按此表导航，避免全库扫描。

```text
acme/
├── .ai/          # AI 配置中心（唯一事实源，见 .ai/ARCHITECTURE.md）
├── .ai-memory/   # 知识与记忆落盘（decisions / playbooks / drafts）
├── .ai-tmp/      # AI 临时文件区（内容不入库）
├── workspace/    # 实际工作目录（见 WORKSPACE.md）
│   ├── apps/     #   应用代码（见 apps/APPS.md）
│   └── env-stack/#   环境搭建（见 env-stack/ENV-STACK.md）
└── docs/         # 项目文档
```

## 环境与 MCP 连接

- 环境三态与 MCP 直连定义：`.ai/env/{dev,test,prod}.yaml`（dev/test 读写，**prod 只读**；Schema 与纪律见 `.ai/env/ENV-GUIDE.md`，严禁明文密钥）。
- 环境搭建 `workspace/env-stack/`；应用代码 `workspace/apps/`。

## 常用命令

| 用途 | 命令 |
|---|---|
| 安装依赖 | TODO |
| 本地运行 | TODO |
| 运行测试 | TODO |
| 构建 | TODO |
| 代码检查 | TODO |

## 术语表

| 术语 | 含义 |
|---|---|
| TODO | |
