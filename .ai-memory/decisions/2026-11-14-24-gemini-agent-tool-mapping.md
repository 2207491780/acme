# Gemini 子代理工具名派生映射（池保持 Claude 风格单一源）

日期：2026-11-14

## 背景

2026-11-14 全面审查发现：公共池 agent 的 frontmatter `tools` 用 Claude Code 工具名（`Read/Write/Edit/Grep/Glob/Bash`），派生到 `.gemini/agents/` 后 Gemini CLI（≥ v0.24 支持项目级子代理）无法识别——其内置工具名为 `read_file/write_file/replace/grep_search/glob/run_shell_command`，未知名大概率导致工具限制静默失效或子代理不可用。同时 `model: inherit` 是 Claude 写法，Gemini 的 model 字段缺省即继承主会话。

## 决定

1. 池内源文件**保持 Claude Code 工具名**（单一事实源，不引入每工具分支）。
2. 引擎 `derive()` 增加 per-target `transform`：派生到 `.gemini/agents` 的 .md 在 frontmatter 内做工具名映射（`GEMINI_TOOL_MAP`）并移除 `model: inherit` 行；正文不受影响；其他目标不套用。
3. 回归锁定：`sync.test.mjs` 新增 `should_map_claude_tool_names_for_gemini_agents_on_derive`（Gemini 映射 + model 移除 + 正文不动 + Claude 原样 + --check 幂等）。
4. 工具名对照（Gemini CLI 官方 Tools reference）：Read→read_file、Write→write_file、Edit→replace、Grep→grep_search、Glob→glob、Bash→run_shell_command。

## 被否的选项

- **池文件按工具写两份 tools 字段**：否决——双份维护违反单一事实源精神（同 ADR-19 的 ZCode 取舍）。
- **派生到 Gemini 时直接删除 tools 字段（继承全部工具）**：否决——tester「无 Bash 只写不跑」、diagnostic「纯只读」的工具面约束会失效，仅剩 prompt 约束。
- **Gemini 专属同名覆盖文件放 `.ai/tools/gemini/agents/`**：否决——引擎的派生目标冲突检测会拒绝公共池与工具专属写同一目标；且造成双源漂移。
- **冒烟通过前判定失效并回滚派生**：否决——映射后的 frontmatter 与 Gemini Schema 兼容（name/description 必填已满足），先落地映射再由用户侧冒烟确认（B2 项）。
