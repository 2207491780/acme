# AI 配置治理优化：CodeBuddy 纳管、AI 路径单一清单、flex-test-assembly 改写拒绝

日期：2026-11-17
背景关联：`2026-11-14-20-zcode-stub-local-only-check.md`（localOnly 语义）、`2026-11-14-21-remove-iflow-support.md`（iFlow 裁撤）、`2026-11-14-22-unify-ai-path-scope.md`（三层路径口径统一）。

## 背景

1. **`.codebuddy/` 游离于治理外**：根目录 `.codebuddy/` 未跟踪、git 未忽略、未在 `sync.mjs` 登记任何派生键——
   内含手抄的 3/5 公共池 agent（backend-dev/frontend-dev/tester，缺 ai-config-editor/diagnostic），带
   「派生自 .ai/agents/…」式标记，但孤儿检测只扫描 TOOLS 登记的目标目录，对它不可见。实地核查（2026-11-17）：
   三份手抄副本正文当前与 `.ai/agents/` 原文逐字一致（仅派生标记的措辞与位置不同），但一致性纯靠手工维持——
   公共池源任何更新都不会联动到该目录，缺失的 2 个 agent 也不会被补齐，漂移是结构性必然而非已发生事实。
2. **AI 路径清单三处手工维护、无机器约束**：同一路径清单逐字重复在 `.ai/hooks/pre-commit`（shell）、
   `.ai/hooks/mirror-check.mjs`（JS 数组）、`.ai/ci/ci.yml` mirror-sync 作业（YAML 内联，且同一作业内重复两次）。
   注释声称「同口径」但仅靠人工保持。实地核查（2026-11-17）：三处内容当前一致（各 15 条），仅排序写法不同
   （ci.yml 把 `.gitignore` 排在根部入口之后）——即清单内容尚未漂移，但约束缺位：任何一处单独增删路径都会
   静默失同步，且三处注释互指对方为口径权威。`.codebuddy` 的漏网是治理盲区的实证（它本就不应入镜像清单，
   但没有任何机制能区分「有意不列」与「漏列」）。
3. **`flex-test-assembly.md` 工作区改写被拒绝**：某会话试图改写该手册，声称代码库存在 `IntegrationTestBase` /
   `@IntegrationTest` 集成测试基类并将 JDK 口径写为 21。实地核查（2026-11-17）：backend 全量 grep 两名均 0 命中
   （虚构知识）；本机 `mvn -version` 运行时为 Java 25.0.2、CI `ci.yml` 为 `java-version: 25`、HEAD 版手册写
   「JDK 25」——改写与三方事实冲突。改写被拒绝，工作区恢复 HEAD；HEAD 版是实战踩坑沉淀（三层测试环境、
   8 条装配口径、参考实现清单）。

## 决定

1. **CodeBuddy 以 localOnly 派生键纳管**：`TOOLS` 增 `codebuddy` 键（`target: .codebuddy`、`localOnly: true`，
   语义同 zcode：git 忽略不入库、`--check` 缺失跳过不判错、存在必须一致）；`.codebuddy/` 加入 `.gitignore`；
   新增 `mirrorPools: ['agents', 'commands']`——公共池 5 个 agent + `/req` 命令**全量拷贝**派生到
   `.codebuddy/agents|commands/`（实地核对：手抄副本 frontmatter 与 `.ai/agents/` 原文逐字一致，CodeBuddy
   原生识别该格式，无需格式适配）；目录说明源 `.ai/tools/codebuddy/CODEBUDDY-CLI.md` 照常派生。孤儿检测随
   TOOLS 登记自动覆盖。
2. **AI 路径单一清单 + sync 一致性校验**：新建 `.ai/hooks/ai-paths.list`（15 条 = 三处现有清单并集，含顺序；
   iFlow 不复活、localOnly 的 `.zcode/`/`.codebuddy/` 有意不入清单——清单内注释注明）。`pre-commit` 改为逐行
   读取该文件（`#` 与空行跳过），`mirror-check.mjs` 改为按脚本自身位置 `fs` 读取（任意 worktree 根成立）；
   `ci.yml` mirror-sync 保留**内联副本**（收敛为单个 `AI_PATHS=(...)` 数组），`sync.mjs` 新增校验：内联与清单
   逐条（含顺序）不一致即失败，sync 与 `--check` 同 gating；`.ai/tests/ai-paths.test.mjs` 回归锁定三处消费
   方式与清单内容。清单自身位于 `.ai/hooks/` 下，由首条 `.ai` 路径覆盖其「必须完整暂存」检查。
3. **`flex-test-assembly.md` 维持 HEAD 版**：拒绝引入虚构类名与错误 JDK 口径；手册内容以代码库实地核查为准
   （参考实现照抄既有类：`SystemIntegrationTest` 等）。本记录落盘以阻止后续会话再次以虚构知识改写。

## 被否的选项

- **CodeBuddy 走 zcode 式引用存根（手写指针）**：否决——现有 `.codebuddy/agents/` 手抄副本即全量正文，未验证
  CodeBuddy 子代理有「运行时回读源文件」的等效机制；引用存根会改变既有用法。全量镜像零信息损失，且与公共池
  的一致性由引擎保证。
- **`.codebuddy/` 提交入库（取消 git 忽略）**：否决——与 `.zcode/` 同理是本机工具数据；入库会把本机工具目录
  强加给 CI 与协作者。localOnly 的「缺失跳过」语义正是为此设计（ADR 2026-11-14-20）。
- **ci.yml 改为运行时读取 ai-paths.list**：否决——workflow shell 现场解析清单（含 `:(glob)` pathspec 的引号
  处理）引入运行时失败面；内联副本 + sync 写前强校验 + 回归测试三重锁定，把一致性判定前移到派生/CI 静态期。
- **清单比较放宽为「集合相等、不计顺序」**：否决——逐条含顺序的强一致实现最简、误报为零；清单按
  「目录 → 根文件 → glob」排列本身无语义成本，弱化比较只增加实现分支。
- **接受 flex-test-assembly.md 的改写**：否决——`IntegrationTestBase`/`@IntegrationTest` 不存在，照抄必失败；
  JDK 21 口径与工具链实测（Java 25.0.2）及 CI（java-25）冲突；HEAD 版为踩坑沉淀，改写无增益。
