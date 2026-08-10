# Doc Canvas 问题排障手册

> 记录本项目开发过程中遇到的主要问题和解决方案，供后续借鉴。

---

## 1. 卡片删除后刷新又出现（双状态源）

**现象**：在画布上删除卡片后刷新页面，被删卡片又出现，与新生成的卡片叠加。

**根因**：`deleteSelected` 只调 `editor.deleteShapes` 删 tldraw shape，从不调 `api.deleteCard`。刷新时 `mergeMissingCards` 把后端 cards 表当唯一权威，快照里缺 shape 就重建 → 已删卡片复活。

**解决**：`deleteSelected` 中遍历选中 shape，反查 `meta.cardId`，先调 `api.deleteCard` 成功后再 `deleteShapes`，同时从 `knownCardIdsRef` 移除。

**涉及文件**：`frontend/src/components/whiteboard/whiteboard-editor.tsx`

---

## 2. 卡片/线布局相交

**现象**：整理按钮后卡片和线仍然重叠、箭头穿过卡片。

**根因**：`autoLayoutTldraw` 中箭头 binding 遍历有 bug——`from`/`to` 变量计算相同（`binding.fromId === arrow.id ? binding.toId : binding.fromId`），连线方向错乱，分层全凭运气。布局方向也不合理（同层纵向堆叠）。

**解决**：
1. 正确区分 binding 端点：binding 的 `toId` 是端点 shape，`fromId` 是箭头自身。两端点两两相连构建无向图
2. 布局改为每层内节点横向并排（y 同层），层与层纵向堆叠

**涉及文件**：`frontend/src/components/whiteboard/whiteboard-editor.tsx`（`autoLayoutTldraw` 函数）

---

## 3. 画布交互体验差

**现象**：无法拖动画布（只能长按滑到边）、添加元素位置固定不随页面变化。

**根因**：
- `hideUi` 隐藏 tldraw 自带手工具/缩放控件，自建工具栏无平移入口
- `nextPlacement` 用固定页面坐标 (120,120)，滚动后添加元素仍落回原点附近
- `autoFocus={false}` 导致空格平移首击无效

**解决**：
1. 工具栏加"平移"按钮 → `setTool("hand")`
2. `addNote/addText/addShape/addImage` 改用 `viewportPlacement`（视口中心为锚点）
3. `onMount` 中 `editor.focus()` + `setCameraOptions({ wheelBehavior: "pan" })`
4. 默认提示文案改为"滚轮/中键拖动平移画布"

**涉及文件**：`frontend/src/components/whiteboard/whiteboard-editor.tsx`

---

## 4. Delete 键删除不生效

**现象**：选中元素按 Delete/Backspace 没反应。

**根因**：`hideUi` 移除了 tldraw 内置的 UI 层，包括 Delete/Backspace 快捷键的 action 绑定。

**解决**：在 whiteboard-editor 加全局 keydown 监听。选中元素 + 不在输入框内时，Delete/Backspace 调用 `deleteSelected`。同时保留自定义删除按钮。

**涉及文件**：`frontend/src/components/whiteboard/whiteboard-editor.tsx`

---

## 5. Ctrl+Z 回滚（undo/redo）

**现象**：没有撤销/重做功能。

**根因**：tldraw 内置 undo/redo 历史，但 `hideUi` 移除了快捷键绑定。删除时后端卡片也被删，undo 恢复 shape 后后端记录缺失。

**解决**：
1. 全局 keydown 绑定 Ctrl+Z → `editor.undo()`，Ctrl+Shift+Z → `editor.redo()`
2. 删除时把卡片数据存 `deletedCardsRef`（墓碑）
3. `store.listen` 检测 undo 后 shape 重新出现 → `api.createCard` 重建后端卡

**涉及文件**：`frontend/src/components/whiteboard/whiteboard-editor.tsx`

---

## 6. 卡片不可自由缩放 / Markdown 不渲染

**现象**：卡片不能调大小，AI 生成的 `##`/`**`/列表原样显示为纯文本。

**根因**：普通卡片用 tldraw 原生 `note` shape（`resizeMode:'none'`，宽度恒 200px）。`toRichText` 纯按 `\n` 分行不做 markdown 解析。

**解决**：
1. 新建 `CardShapeUtil` 继承 `BaseBoxShapeUtil`（自带 w/h 自由缩放）
2. `component` 用 `ReactMarkdown + remarkGfm` 渲染 markdown
3. 安装 `react-markdown@10 + remark-gfm@4`
4. 旧快照 note/geo 卡片经 `migrateLegacyCardShapes` 自动迁移

**涉及文件**：
- `frontend/src/lib/whiteboard/card-shape-util.tsx`
- `frontend/src/components/whiteboard/whiteboard-editor.tsx`
- `frontend/src/app/globals.css`

---

## 7. 前端编译报错 `.ts` 文件找不到

**现象**：`Build Error: Failed to read source code from card-shape-util.ts`

**根因**：Next.js dev server 的 `.next` 缓存记住了旧文件名（从 `.ts` 重命名为 `.tsx` 时）。

**解决**：杀掉前端进程，删 `.next` 目录，重启前端。

**涉及文件**：`rm -rf frontend/.next` + 重启 `npm run dev`

---

## 8. apinebula.ai 网关 Spring AI 不兼容

**现象**：所有 chat 调用（含工具/不含工具/json_object 等）均返回 `Error while extracting response for type [ChatCompletion] and content type [application/json]`，伴随 `end-of-input`。curl 却正常。

**根因**：Spring AI 1.0.0 的 `OpenAiApi` 发出的 HTTP 请求与 apinebula.ai 网关存在兼容性问题（可能是 header/UA/参数编码细节）。无论换 JDK HttpClient 还是 Apache HttpClient5，无一例外。

**解决**：切换到**阿里百炼 DashScope 兼容模式**：
- `GPT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode`
- 密钥：`BAILIAN_API_TOKEN`（系统环境变量 Machine，35 位）

**涉及文件**：
- `.env`
- `backend/src/main/resources/application.yml`
- `run-backend.ps1`

---

## 9. Spring AI tool calling 在百炼上不兼容

**现象**：`collectContext` 发 tools 请求后百炼返回 `end-of-input`。

**根因**：百炼 DashScope 对 Spring AI 的 tools 请求格式兼容不完整（curl 测试 tools 参数 200 OK，但 Spring AI 内部格式不同）。

**解决**：暂时用 `inlineContext` 替代 `collectContext`——直接把卡片/资料信息拼入 user prompt，不额外发起 chat+tool 调用。

**涉及文件**：`backend/src/main/java/com/picturecreate/doccanvas/service/ai/SpringAiGenerationService.java`

---

## 10. Spring AI entity() 在百炼上不兼容

**现象**：`.call().entity(BoardActionPlan.class)` 返回 `end-of-input`。

**根因**：`.entity()` 使用 json_schema 响应格式，百炼对此支持不完整。

**解决**：改为 `.call().content()` 取纯文本，手动 JSON 解析（`parseActionsFromContent`）。

**涉及文件**：`backend/src/main/java/com/picturecreate/doccanvas/service/ai/SpringAiGenerationService.java`

---

## 11. EmbeddingClient 编译失败

**现象**：`ArrayList cannot be resolved to a type`。

**根因**：改 EmbeddingClient 构造器时删掉了 `import java.util.ArrayList`。

**解决**：补回 import。

**涉及文件**：`backend/src/main/java/com/picturecreate/doccanvas/service/ai/EmbeddingClient.java`

---

## 12. 文件向量化外键约束冲突

**现象**：上传文件后立即删除，后端报 `insert on document_chunks violates foreign key constraint`。

**根因**：向量化是事务提交后异步执行（afterCommit + vectorizeExecutor），文件被删除时向量化任务还在跑 → source_files 记录已不在 → 写 document_chunks 外键冲突。

**解决**：`DocumentChunkService.vectorize` 在写 chunk 前检查 `sourceFileRepository.existsById(sourceFileId)`，不存在就跳过。

**涉及文件**：`backend/src/main/java/com/picturecreate/doccanvas/service/DocumentChunkService.java`

---

## 13. HttpComponentsClientHttpRequestFactory 类型陷阱

**教训**：httpclient5 5.x 的 `RequestConfig` 的 `setConnectTimeout` 接受 `org.apache.hc.core5.util.Timeout` 而非 `java.time.Duration`；Spring 6.2 的 `HttpComponentsClientHttpRequestFactory.setConnectTimeout(int)` 已 deprecated。直接用 `HttpComponentsClientHttpRequestFactory` 构建，不接受 `Duration` 类型。

**最终方案**：用最简单的 `HttpClients.custom().build()` 构建 client，`HttpComponentsClientHttpRequestFactory` 设 `setConnectTimeout(int Ms)`。

---

---

## 14. 提示词设计缺陷：一刀切的字数要求

**现象**：
1. 用户问"你是谁"时直接执行工作流，把对话内容写入画布卡片
2. 流程图和思维导图卡片内容过长（每张卡 300+ 字），不符合结构图精简要求
3. 总结资料类任务需要详实，结构图类任务需要扼要，当前统一约束 300 字导致结构图冗余

**根因**：
1. 系统没有"非任务意图识别"——所有用户输入一律发起 AI 任务，模型按要求"必须生成 JSON 动作"，闲聊也生成动作落库
2. `systemPrompt` 中硬编码 `不少于 300 字`，未按任务类型差异化
3. 未给模型"闲聊/非生产意图"的兜底约束

**解决**：
1. `contentDepthConstraint(taskType)`：按任务类型分两类约束
   - 内容类（DOCUMENT_SUMMARY / STUDY_PLAN / PRODUCT_REQUIREMENT）→ 详实 300 字
   - 结构图类（MINDMAP / FLOWCHART / CANVAS_STRUCTURE）→ 精简 1-3 句话，关键词/短语
2. `chatGuard`：对非生产型意图（闲聊/打招呼/问身份）约束模型"只输出一张最小回复卡，不执行多余动作"
3. 前端 `inferTaskType` 兜底：闲聊类输入默认走 CANVAS_STRUCTURE（不会生成大量卡片）

**涉及文件**：
- `backend/src/main/java/com/picturecreate/doccanvas/service/ai/SpringAiGenerationService.java`（systemPrompt/contentDepthConstraint/chatGuard）
- `frontend/src/components/board/task-panel.tsx`（inferTaskType）
