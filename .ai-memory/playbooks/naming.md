# 命名规范手册（naming）

> 统一本仓库**文件 / 文件夹 / 包 / 标识符**的命名格式。规则结论见 `.ai/rules/coding-standards.md`（拼接进所有工具入口）；本手册是完整口径，按需读取。
> 触发：新增或重命名文件/目录/包/模块、编写文档时。

## 总则

- 名字只用 ASCII 字母/数字与 `.` `-` `_`；禁空格、中文、其他特殊字符。UTF-8 无 BOM、LF。
- 沿用同目录既有风格；与既有文件冲突时以本手册为准，但**重命名须任务明确要求**，不顺手批量改名。
- **大小写敏感红线**：Windows/macOS 默认大小写不敏感 FS，仅大小写变更用 `git mv` 两步法（先改临时名、再改目标名），避免 git 丢失 rename 历史。
- **派生文件与框架/工具固定名一律不改**（见第 7 节豁免清单）。

## 1. Markdown 文档（按角色二分）

| 角色 | 格式 | 例 |
|---|---|---|
| 导航/总览/架构/指南/入口（面向人） | **UPPER-KEBAB**（单词全大写，多词连字符） | `README.md`、`AGENTS.md`、`ARCHITECTURE.md`、`GUIDE.md`、`ENV-GUIDE.md`、`AGENT-POOL.md`、`UI-SPEC.md` |
| 内容源（规范/手册/决策/代理/技能正文，面向 AI 消费或拼接） | **lowercase-kebab** | `coding-standards.md`、`ai-config.md`、`flex-test-assembly.md`、`ai-config-editor.md` |

- **目录总览/说明文档 = 目录名的大写形式**（kebab 目录 → UPPER-KEBAB.md）：`workspace/`→`WORKSPACE.md`、`apps/`→`APPS.md`、`env-stack/`→`ENV-STACK.md`、`backend/`→`BACKEND.md`、`web-console/`→`WEB-CONSOLE.md`。每个应用目录放一份，写清「做什么 / 怎么跑 / 依赖什么」。
- **决策记录（ADR）** = `YYYY-MM-DD-NN-<slug>.md`（NN 当日序号，slug 用 kebab）；格式与纪律见 `../decisions/ADR-GUIDE.md`。
- **手册/规范源** = 语义 kebab 小写（`naming.md`、`ai-config.md`）；新增手册在 `PLAYBOOK-GUIDE.md` 登记，并在 `.ai/rules/` 挂触发条款。
- 技能正文固定 `SKILL.md`（框架要求，豁免）；代理文件 = 代理名 kebab（`ai-config-editor.md`）。

## 2. 文件夹 / 目录

- 自定义目录一律 **lowercase-kebab**：`acme-cloud`、`common-util`、`framework-mybatis-flex`、`env-stack`、`business-content`、`scripts`、`config`、`docker`。
- 模块分层前缀：`acme-`（工程）、`common-`/`framework-`/`platform-`/`business-`（层）；**目录名 = Maven artifactId**。
- **Java 包目录** = 全小写、无连字符/下划线，按包层级展开：`com/acme/business/content`。
- 禁自定义目录用大写、下划线、空格、中文（框架固定名除外）。

## 3. Java

- **包** = 全小写点分 `com.acme.<层>.<模块>[.<子>]`，禁大写/下划线/连字符。包名取模块语义名（可缩写）：`framework-mybatis-flex` 模块 → `com.acme.framework.flex` 包。
- **类/接口/枚举/record/注解** = PascalCase 名词：`AcmeGatewayApplication`、`ContentQueryUseCase`、`FlexRepositorySupport`。
- **测试** = `<被测类>Test`（单元）/ `<Class>SliceTest`（H2 切片）/ `<Class>IntegrationTest`（Testcontainers 集成）。
- **常量** = UPPER_SNAKE_CASE；**方法/变量** = camelCase。`package-info.java` 固定小写。

## 4. Maven

- `groupId` = `com.acme`（全小写点分）；`artifactId` = 模块目录名（kebab）；`pom.xml` 固定。
- 版本经 `revision`（`1.0-SNAPSHOT`）统一；依赖/插件版本在 `acme-parent` 管理。

## 5. 前端（TypeScript / React）

- **单组件/页面/屏幕文件** = PascalCase.tsx：`App.tsx`、`ReleaseFormModal.tsx`、`DashboardScreen.tsx`。
- **非组件模块**（hooks/utils/store/atoms/icons/theme/types，含集合型 .tsx）= camelCase 或 lowercase：`useStore.ts`、`utils.ts`、`theme.ts`、`icons.tsx`、`ui.tsx`、`main.tsx`（入口）。
- **标识符**：组件/类型 PascalCase，函数/变量 camelCase，常量 UPPER_SNAKE_CASE。
- **测试** = `<subject>.test.ts(x)`：`reducer.test.ts`、`store.test.ts`。
- kebab 变体（`parse-state.ts`）允许，同目录优先沿用既有风格，不为此批量改名。
- 框架固定名不改：`vite.config.ts`、`vitest.config.ts`、`global.d.ts`、`index.ts`、`main.tsx`、`package.json`、`tsconfig*.json`。
- **静态资源/样式** = lowercase-kebab：`global.css`、`icon.svg`；HTML 入口 `index.html` 固定，生成/模板页 kebab（`style-guide.html`、`style-guide.template.html`）。
- **Electron 壳层** `.js`/`.cjs` = lowercase-kebab（`app-protocol.js`、`security.js`）；入口 `index.js`/`index.cjs` 与 `babel.config.js`/`metro.config.js` 固定。

## 6. 脚本 / 配置 / 资源文件

- 脚本 = lowercase-kebab `.mjs`/`.sh`：`sync.mjs`、`build-style-guide.mjs`、`deploy.sh`；脚本测试 = `<subject>.test.mjs`。
- 配置 = lowercase-kebab `.yml`/`.yaml`：`ci.yml`、`cd.yml`、`cloud-dev.yaml`。
- **SQL 迁移** = Flyway `V<版本>__<snake_描述>.sql`（`V1__init_schema.sql`）；测试建表 `schema.sql`、DDL 模板按表名 snake（`shedlock_locks.sql`）。
- **i18n 资源束** = `i18n/<模块>-messages_<lang>.properties`（ResourceBundle 约定，locale 后缀用下划线 `_en`/`_zh`）。
- **Spring 自动装配** = `org.springframework.boot.autoconfigure.AutoConfiguration.imports`、`spring.factories`（框架固定名）。
- 框架/工具固定名不改：`application.yml`、`pom.xml`、`pnpm-workspace.yaml`、`pnpm-lock.yaml`、`Dockerfile`、`.gitignore`、`.gitattributes`、`.env`、`.mcp.json`。

## 7. 豁免清单（名字由派生引擎/工具/框架固定，禁改）

- **派生入口**：根 `AGENTS.md`/`CLAUDE.md`/`GEMINI.md`/`CODEBUDDY.md`、`.ai/RULES.md`、`.qoder/rules/project_rules.md`、`.trae/rules/project_rules.md`、`.cursor/rules/project.mdc`、`.github/copilot-instructions.md`（名字由 `.ai/sync.mjs` 与工具约定决定，snake/kebab 不算违规）。
- **工具目录**：`.claude`/`.cursor`/`.gemini`/`.qoder`/`.trae`/`.github`/`.idea`。
- **体系目录**：`.ai`/`.ai-memory`/`.ai-tmp`。
- **Java/构建**：`META-INF`、`src/main/java`、`src/test/resources`、`target`、`pom.xml`、`.flattened-pom.xml`（flatten 插件生成）。
- **前端/Node**：`node_modules`、`dist`、`vite.config.ts`、`package.json`、`pnpm-*.yaml`。
- **技能**：`SKILL.md`。
- **数据/资源/其他固定名**：Flyway `V*__*.sql`、`schema.sql`、`*.imports`、`spring.factories`、`index.html`、`manifest.webmanifest`、`.npmrc`、`.gitkeep`、`*.iml`（IDE）。

## 落地检查

- 新增名字前对照本手册选格式；重命名既有文件须任务明确要求，大小写变更用 `git mv` 两步法。
- 改名后同步更新所有引用（链接/导入/配置）；**ADR 历史记录与 IDE 状态（`.idea/`）不改**。
- 触及 `.ai/` 源后跑 `node .ai/sync.mjs` + `--check`（拼接后 `RULES.md` 字节预算见 `.ai/ai-coding-config.yml`）。