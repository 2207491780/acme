-- business-content 基线种子（架构设计 3.6 / business-api-integration）：
-- ①分类种子（对齐 DemoContent.categories 除「全部」，QD-09 编码+名称）；②热搜词种子（对齐 DemoContent.hotSearches）；
-- ③内容种子（对齐 DemoAdminContents.rows 10 行的状态/作者/views/时间 + DemoContent.items 摘要，正文逐篇可展示文本，AC-005）。
-- 幂等口径（platform-system V2 先例）：固定主键（小数值 ID 与雪花大数值无碰撞）+ ON CONFLICT (id) DO NOTHING。
-- views 为存量种子值（Q-14=B）；created_by/updated_by='system'；status 取值与 ContentStatus 枚举名一致（小写）。

-- 1. 分类种子（sort_order 对齐演示清单顺序）
INSERT INTO biz_content_category (id, code, name, sort_order,
                                  created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, 'operation',   '运营',   1, now(), 'system', now(), 'system', 0, false),
    (2, 'brand',       '品牌',   2, now(), 'system', now(), 'system', 0, false),
    (3, 'methodology', '方法论', 3, now(), 'system', now(), 'system', 0, false),
    (4, 'governance',  '治理',   4, now(), 'system', now(), 'system', 0, false),
    (5, 'design',      '设计',   5, now(), 'system', now(), 'system', 0, false),
    (6, 'growth',      '增长',   6, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 2. 热搜词种子（BR-014 服务端顺序 sort_order 1..n）
INSERT INTO biz_hot_search_word (id, keyword, sort_order,
                                 created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, '内容运营方法论', 1, now(), 'system', now(), 'system', 0, false),
    (2, 'AIGC 声明',     2, now(), 'system', now(), 'system', 0, false),
    (3, '同城破圈',       3, now(), 'system', now(), 'system', 0, false),
    (4, '选题三角',       4, now(), 'system', now(), 'system', 0, false),
    (5, '私域分发',       5, now(), 'system', now(), 'system', 0, false),
    (6, '季度复盘',       6, now(), 'system', now(), 'system', 0, false)
ON CONFLICT (id) DO NOTHING;

-- 3. 内容种子（10 行；updated_at 对齐演示行时间 +08 时区；excerpt 对齐 DemoContent.items，无对应者补写）
INSERT INTO biz_content (id, title, category_code, author, status, views, excerpt, content, cover,
                         created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    (1, '第三季度内容运营复盘：增长与留存双曲线', 'operation', '陈序', 'published', 12580,
     '从选题池建设到分发链路调优，季度复盘拆解增长与留存的两组关键曲线。',
     E'本季度我们围绕选题池建设、分发链路调优两件事做了系统性投入，本文是完整复盘。\n\n增长曲线：新客触达主要来自首页推荐流与搜索联想两条通路，推荐流贡献了约六成新增曝光；选题池的标签化治理让命中率提升明显。\n\n留存曲线：收藏与关注的联动策略初见成效，收藏内容的 7 日回访率环比上升。下季度将把复盘口径固化为月度例行。',
     NULL,
     timestamptz '2026-10-24 14:20:00+08', 'system', timestamptz '2026-10-24 14:20:00+08', 'system', 0, false),

    (2, '品牌故事：一杯茶饮的破圈之路', 'brand', '吴限', 'reviewing', 8642,
     '区域品牌如何借助内容矩阵完成从本地口碑到全国话题的跨越。',
     E'区域品牌如何借助内容矩阵完成从本地口碑到全国话题的跨越？我们跟访了这个茶饮品牌的完整破圈路径。\n\n第一步是本地口碑的积累：门店内容化，把制作过程、原料溯源做成系列短视频；第二步是话题化突破：借势节点策划联名内容，把区域话题推向全国榜单。\n\n内容矩阵的关键是节奏：日常内容稳住基本盘，节点内容冲击峰值，两者缺一不可。',
     NULL,
     timestamptz '2026-10-24 11:05:00+08', 'system', timestamptz '2026-10-24 11:05:00+08', 'system', 0, false),

    (3, '短视频选题方法论：热点、情绪与信息增量的三角', 'methodology', '陈序', 'published', 23911,
     '选题不是追热点，而是在热点、情绪与信息增量之间找到平衡点。',
     E'选题不是追热点，而是在热点、情绪与信息增量之间找到平衡点。本文给出可执行的三角评估法。\n\n热点维度看时效窗口：入场太早教育成本高，太晚流量被分完；情绪维度看共鸣强度：争议、治愈、成就感是三类高转化情绪；信息增量维度看差异化：同样的事件，你的解释框架是什么？\n\n实操建议是给选题打分：三个维度各 1～5 分，总分低于 9 分的选题直接放弃。',
     NULL,
     timestamptz '2026-10-23 18:42:00+08', 'system', timestamptz '2026-10-23 18:42:00+08', 'system', 0, false),

    (4, 'AIGC 声明与创作者伦理：平台治理实践', 'governance', '林然', 'draft', 0,
     'AI 生成内容如何声明、审核与追责，一份可落地的平台治理清单。',
     E'AI 生成内容如何声明、审核与追责？本文整理一份可落地的平台治理清单。\n\n声明层面：凡使用生成式 AI 产出的实质性内容，须在显著位置标注；混合创作按占比分级声明。审核层面：AI 辅助内容与人工内容同一审核标准，但增加事实性核查抽样。追责层面：声明不实的内容一经核实即下架，并记录创作者信用分。\n\n治理的目标不是限制创作，而是保住读者的信任。',
     NULL,
     timestamptz '2026-10-23 09:30:00+08', 'system', timestamptz '2026-10-23 09:30:00+08', 'system', 0, false),

    (5, '同城内容运营：从地理标签到社区氛围', 'operation', '吴限', 'published', 15230,
     '同城栏目不止是 LBS 分发，更是本地社区氛围的养成器。',
     E'同城栏目不止是 LBS 分发，更是本地社区氛围的养成器。\n\n地理标签解决的是「找到附近的人和事」，社区氛围解决的是「愿意留下来」。运营上要抓三件事：本地话题的持续供给、线下活动的内容化反哺、同城创作者的分层扶持。\n\n冷启动阶段建议先做窄：一城一策，跑通单城模型再复制。',
     NULL,
     timestamptz '2026-10-22 20:15:00+08', 'system', timestamptz '2026-10-22 20:15:00+08', 'system', 0, false),

    (6, '数据看板设计：让指标说人话', 'design', '林然', 'rejected', 3421,
     '看板设计的核心是把指标翻译成决策语言，而非堆砌图表。',
     E'看板设计的核心是把指标翻译成决策语言，而非堆砌图表。\n\n先问清楚看板服务谁：管理层看结果指标与趋势，运营看过程指标与归因。再定结构：北极星指标置顶，驱动指标分组呈现，异常指标显式告警。\n\n视觉上坚持一屏一个主题，图表类型服从比较关系——比较用柱、趋势用线、构成用堆叠。',
     NULL,
     timestamptz '2026-10-22 16:08:00+08', 'system', timestamptz '2026-10-22 16:08:00+08', 'system', 0, false),

    (7, '收藏与关注：私域内容分发的两驾马车', 'growth', '陈序', 'reviewing', 987,
     '收藏代表长期价值，关注代表关系资产，两者的分发策略截然不同。',
     E'收藏代表长期价值，关注代表关系资产，两者的分发策略截然不同。\n\n收藏分发追求「适时重现」：用户收藏的是未来的自己需要的东西，回访场景要精准；关注分发追求「新不掉线」：创作者的新内容必须稳定触达，否则关系资产会衰减。\n\n私域分发的组合拳是：关注保底流量打底，收藏召回做增量，两者在推荐流里配比调优。',
     NULL,
     timestamptz '2026-10-21 10:22:00+08', 'system', timestamptz '2026-10-21 10:22:00+08', 'system', 0, false),

    (8, '投稿向导的体验设计：三步完成一次高质量投稿', 'design', '吴限', 'published', 18765,
     '分步表单、实时校验与草稿恢复，如何降低创作者的投稿门槛。',
     E'分步表单、实时校验与草稿恢复，如何降低创作者的投稿门槛。\n\n三步结构：先选题填标题（最低成本拿到意图），再完善正文与素材，最后预览发布。每一步只暴露当下必要字段，其余字段按需展开。\n\n体验细节决定完成率：实时校验给出可执行的修复提示，草稿自动保存防止意外丢失，失败态保留已填内容。',
     NULL,
     timestamptz '2026-10-21 08:55:00+08', 'system', timestamptz '2026-10-21 08:55:00+08', 'system', 0, false),

    (9, '内容冷启动的 72 小时：从 0 到 1 万曝光', 'growth', '林然', 'draft', 0,
     '新内容上线后的黄金 72 小时，如何用节奏化分发跑出第一波曝光。',
     E'新内容上线后的黄金 72 小时，决定了它能不能进入更大的推荐池。\n\n第 0～6 小时是初始分发窗口：私域渠道同步推送，积累首批互动信号；第 6～24 小时是校准窗口：根据完读率与互动率调整封面与标题；第 24～72 小时是放大窗口：表现达标的内容追加资源位。\n\n节奏化分发的核心是让每条内容都拿到一次被验证的机会。',
     NULL,
     timestamptz '2026-10-20 19:40:00+08', 'system', timestamptz '2026-10-20 19:40:00+08', 'system', 0, false),

    (10, '品牌联名内容怎么做：三次成功案例拆解', 'brand', '陈序', 'published', 21450,
     '从选题共创到分发对赌，三次品牌联名的内容打法完整拆解。',
     E'品牌联名内容怎么做？我们复盘了三次成功案例，提炼出可复用的打法。\n\n案例一主打选题共创：品牌与创作者共同定题，内容自然植入卖点；案例二主打分发对赌：双方约定资源位与保底曝光，效果挂钩结算；案例三主打长尾沉淀：联名内容资产化，沉淀进品牌内容库持续分发。\n\n共同点是先共识目标再谈形式——联名不是贴片，是内容层面的合作。',
     NULL,
     timestamptz '2026-10-20 15:12:00+08', 'system', timestamptz '2026-10-20 15:12:00+08', 'system', 0, false)
ON CONFLICT (id) DO NOTHING;
