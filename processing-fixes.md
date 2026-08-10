# 5 个使用问题的分析与规划

## 逐项根因 + 步骤 + 成功率

### 问题 1：删除卡片后刷新又出现（叠加）
**根因（已确认）**：双状态源不一致。
- `deleteSelected` 只调 `editor.deleteShapes` 删 tldraw shape，**从不调 `api.deleteCard`**（前端 `deleteCard` 零调用）。
- 刷新时 `mergeMissingCards` 把后端 cards 表当唯一权威，快照里缺 shape 就重建——已删卡片复活。
- 复活的卡用默认网格坐标 `nextPlacement`，与 AI 新卡叠加。
- 次要：`knownCardIdsRef` 删除后不更新；1s 防抖窗口内刷新旧快照带回已删 shape。

**修复步骤**：
1. `deleteSelected` 遍历选中 shape，反查 `meta.cardId`，先 `api.deleteCard` 成功后再 `deleteShapes`，并从 `knownCardIdsRef` 移除。
2. IMAGE shape 的 `createCardShape` 补 `meta: { cardId }`（当前缺失）。
3. （加固）位置/尺寸变更回写 `api.updateCard` 的 x/y/width/height，消除后端坐标陈旧导致落回默认网格。

**成功率：90%**（根因明确、改动局部、后端接口已存在）

### 问题 3：无法拖动画布 / 添加元素位置固定
**根因（已确认）**：
- `hideUi` 隐藏了 tldraw 自带手工具/缩放控件，自建工具栏又**没有"手/平移"按钮**。
- 空格+拖动/中键拖动是 tldraw 原生平移，但用户不知道，且 `autoFocus={false}` 需要先点画布获得焦点。
- `nextPlacement` 用固定页面坐标 (120,120)，滚到别处点添加仍落回原点附近。

**修复步骤**：
1. 工具栏加"手/平移"按钮 → `setTool("hand", ...)`。
2. 加平移动线提示（顶部 message 默认文案补一句）。
3. `addNote/addText/addShape/addImage` 坐标改用 `editor.getViewportPageBounds().center` 为锚点（减半尺寸 + 数量偏移）；`nextPlacement` 保留给 hydration/legacy 用。
4. （可选）`onMount` 里 `editor.focus()` 让空格平移首击生效。

**成功率：95%**（机制明确、改动局部）

### 问题 2：整理/排版逻辑有问题，卡片/线相交
**根因（已确认）**：`autoLayoutTldraw` 有 bug——
- 箭头 binding 遍历时 `from`/`to` 计算相同（`binding.fromId === arrow.id ? binding.toId : binding.fromId` 两个变量一样），`from` 未用，连线方向错乱。
- 每层内**所有节点纵向堆叠在同一 x**，y 依次 +h+V_GAP；层与层横向推进。但同一层节点在**同一列**（x 相同），若层内节点多会纵向挤，且箭头连线方向错乱导致分层错误。
- 更根本：分层布局把同一 layer 的节点放同一列，箭头从上层连到下层；但线是"绝对定位"箭头，移动节点后箭头重新吸附。节点相交是因为**只按层推进，未考虑每层内多分支并排**。

**修复步骤**：
1. 修 binding 遍历：正确区分 from/to（`binding.fromId === arrow.id ? binding.toId : binding.fromId`，另一个是 arrow.id 自身）。
2. 布局改为：每层内节点**横向并排**（y 相同，x 依次推进），层与层纵向堆叠——更符合"从上到下流程/思维导图"习惯；或保留横向分层但每层多列网格。
3. 加防重叠：用已放置节点的边界框做碰撞检测，冲突时后移。
4. （可选）整理后 `editor.zoomToFit()` 让结果在视野内。

**成功率：75%**（bug 明确，但布局算法要重写一部分，需调参）

### 问题 4：AI 工具/技能命中率低
**根因（已确认）**：
- 工具只在第一阶段 `collectContext` 对话可用，第二阶段 `entity(BoardActionPlan)` 结构化输出**禁用工具**。
- `collectContext` 是"可选调用"提示，模型经常不调（实测部分任务 0 工具调用）。
- 技能只是把 SKILL.md 塞进 system prompt，未真正约束模型调用工具。

**修复步骤**：
1. 第一阶段 `collectContext` 改为**强制多轮工具调用**：先 `get_board_context` + `list_source_files`（+ 有资料时 `search_document_chunks`），把结果注入第二阶段。
2. 第二阶段结构化的 user prompt 注入"工具已收集的上下文"（已有 toolContext 参数，确保非空）。
3. 技能 prompt 强化：明确"该任务必须调用这些工具"，并列出工具返回什么。

**成功率：70%**（根因明确，但依赖模型行为，需多轮验证调 prompt）

### 问题 5：流程图/思维导图生成简单
**根因**：`structurePrompt` 只让模型输出 1-3 个动作，模型倾向少输出；坐标由模型自行排布，无布局约束；无节点间自动布局兜底。

**修复步骤（需讨论方案）**：
1. **方案 A**：强化 structurePrompt——指定分支数/步骤数下限，明确坐标规则（中心主题居中、分支辐射）。
2. **方案 B**：模型只输出"节点 + 连线"结构（不输出坐标），前端用 `autoLayout` 生成坐标 → 彻底解决布局。
3. **方案 C**：两段式——先让模型生成 JSON 结构，再本地布局器排布。

**成功率：40-50%**（依赖模型结构化能力，需实验；方案 B 前端可控性高但改动大）

---

## 执行策略

按成功率从高到低执行：
1. 问题 3（95%）→ 问题 1（90%）→ 问题 2（75%）→ 问题 4（70%）
2. 问题 5（低成功率）先讨论方案再定。

高成功率的三项先执行，问题 4 做深度迭代，问题 5 讨论。
