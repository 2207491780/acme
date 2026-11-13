# 委派单 {REQ-ID}-{role}
- **任务摘要**：{3-5 行：做什么+落哪里+验收什么；子 Agent 靠此即可开工，不读任何流程文档}
- **输入**：契约 `contracts/{REQ-ID}.md` 的节：{frontmatter / §API / §验收标准 / §变更清单，按角色挑}
  ；范围文档：{frontend→`.ai/scopes/workspace/apps/frontend/AGENTS.md`+UI-SPEC.md；backend→`.ai/scopes/workspace/apps/backend/AGENTS.md`(+flex-test-assembly.md)；tester→各端总览+一个既有用例范本，路径写明}
- **边界**：只写 {目录}；禁读 {目录}；禁止 git；契约歧义就地停止上报，禁止猜测
- **预分配**：{Flyway 迁移版本号等共享资源；无则写"无"}
- **交付物**：{代码：文件清单+每条 AC 落实说明｜用例：文件+AC→用例名映射｜报告：归因结论+证据+建议}
