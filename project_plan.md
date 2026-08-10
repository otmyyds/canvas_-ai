# 单人 AI 文档画布（Doc Canvas）项目计划

> 本文档描述项目的**当前目标与实现方案**，以已落地代码为准。
> 进度盘点见 `processing.md`。

## 1. 项目定位

**单人 AI 文档画布**：用户上传资料、在无限画布中组织卡片，并通过 AI 自动总结、生成思维导图/流程图、删除与整理结构。目标是做出类似 ProcessOn 的**可编辑白板**，卡片支持自由缩放与 Markdown 渲染。

MVP 范围边界：

- 单人使用，不做多人协作、不做实时多人编辑。
- WebSocket 仅用于 AI 任务的实时进度展示与中断控制。
- AI 只产出结构化动作（提案），由后端统一校验、落库、推送事件，不直接写库。

核心目标：

- 创建、保存、打开个人画布；刷新后恢复。
- 无限画布：卡片、文本、多种图形、线条、箭头、图片；卡片可自由缩放、调整颜色/线型/箭头/旋转。
- 上传资料（TXT/Markdown 自动解析），作为 AI 任务上下文。
- AI 任务：总结资料、整理画布、生成思维导图、生成流程图、生成图片等；步骤/进度/中断实时可见。
- AI 输出的卡片正文渲染 Markdown。
- 模板：思维导图、学习计划、产品需求、流程图。
- 私人内容默认只对 owner 可见。

## 2. 总体技术方案

### 前端（Next.js 15 App Router + React 19 + TypeScript）

- 页面与交互 UI：`src/app/`（`/`、`/app`、`/board/[boardId]`）
- 无限画布：**tldraw 3.x**（主编辑器 `whiteboard-editor.tsx`）
- 自定义卡片 shape：`card-shape-util.tsx`（自由缩放 + Markdown 渲染）
- Markdown 渲染：`react-markdown@10` + `remark-gfm@4`
- 状态管理：zustand（`use-board-store`）
- 白板协议层：`lib/whiteboard/schema.ts`（WhiteboardSpec）、`layout.ts`（自动布局）、`templates.ts`（模板）、`tldraw-adapter.ts`
- 属性面板：`element-inspector.tsx`（图形/线型/箭头/颜色/尺寸/旋转）
- WebSocket：`@stomp/stompjs` + `sockjs-client`（`use-task-stream`）
- UI 组件：自研 `components/ui.tsx`（未用 shadcn）
- 富文本编辑器：`@blocknote/*` 已装但业务未接入（卡片用 Markdown 渲染）

### 后端（Java 21 + Spring Boot 3.5）

- Spring Web / WebSocket（STOMP + SockJS）
- Spring Security（`permitAll` 演示占位，无登录）
- Spring Data JPA + Flyway（PostgreSQL + pgvector）
- Redis（任务取消标记）
- Spring AI OpenAI（结构化输出 + 工具调用；`AI_PROVIDER=mock|spring-ai`）
- Spring Retry（重试基础）
- AWS S3 SDK → RustFS（对象存储）
- 校验：Bean Validation + `ErrorHandler` 统一中文错误

### AI 框架

- 接口隔离，避免锁死：

```java
public interface AiGenerationService {
    BoardActionResult generateBoardActions(AiTaskContext context);
}
```

- 实现：`SpringAiGenerationService`（默认，真实模型）、`MockAiGenerationService`（离线演示）。
- 模型策略：`AI_MODEL_PLANNER`（默认 `gpt-5.4`），`AI_MODEL_EMBEDDING`（预留）。
- AI 密钥：系统环境变量 `GPT_API_KEY`（`run-backend.ps1` 提升到进程，不写进仓库）。

## 3. 核心依赖

### 前端

```txt
next / react / react-dom / typescript
tailwindcss
tldraw
zustand
react-markdown / remark-gfm
lucide-react
@stomp/stompjs / sockjs-client
@blocknote/*（已装未接入）
```

### 后端

```txt
spring-boot-starter-web / websocket / security / data-jpa / validation / actuator / data-redis
spring-retry / spring-aspects
flyway-core / flyway-database-postgresql
postgresql
spring-ai-starter-model-openai
software.amazon.awssdk:s3
```

## 4. 运行方式

```powershell
# 基础设施（PostgreSQL+pgvector / Redis / RustFS）
docker compose -f infra/docker-compose.yml up -d

# 后端
./run-backend.ps1        # 加载 .env + 系统环境变量，mvn spring-boot:run

# 前端
./run-frontend.ps1       # 加载 .env，npm run dev
```

环境变量（`.env` / 系统环境变量）：

```env
AI_PROVIDER=spring-ai        # mock | spring-ai
GPT_API_KEY=                 # 建议放系统环境变量，不写仓库
GPT_BASE_URL=https://apinebula.ai   # 不带 /v1（Spring AI 自行拼接）
AI_MODEL_PLANNER=gpt-5.4
AI_MODEL_EMBEDDING=text-embedding-3-large
NEXT_PUBLIC_API_BASE=http://localhost:8080
```

## 5. 认证与权限（JWT，演示兼容）

- **JWT 认证**：`POST /api/auth/register|login|refresh`
  - 注册：BCrypt 加密密码，邮箱唯一
  - 登录：返回 accessToken（30 分钟）+ refreshToken（7 天）+ 用户信息
  - JJWT 0.12.6 HS256，`JwtAuthenticationFilter` 解析 Bearer token → SecurityContext
- `SecurityConfig`：STATELESS、`/api/auth/**`+health+ws 放行、其余 API 需认证、401/403 中文 JSON
- **owner 权限**：服务层按 `owner_id` 过滤（`BoardService.getOwned` 等）
- **演示兼容**：`CurrentUserService` 优先取 JWT 用户，无 token 时回退 `X-User-Email` 请求头/演示用户（本地开发）
- 前端：`/login` 登录注册页，token 存 localStorage，请求带 `Authorization` 头，401 自动跳登录
- **不做 RBAC**：单人应用 owner 即全部权限，无角色/权限表
- 后续可加：多端登录、token 撤销、Redis 会话、refreshToken 强校验

## 6. 画布与卡片

### 6.1 数据模型

- `boards`：画布（owner、title、visibility）
- `board_snapshots`：tldraw 完整快照（`snapshot_json` + version）
- `cards`：AI/接口生成的卡片（title、content_markdown、card_type、x/y/width/height）
- `board_edges`：卡片间连线（from_card_id、to_card_id、label）
- `card_source_files`：卡片 ↔ 资料关联（`@ElementCollection`）

### 6.2 双状态源（已知技术债）

- 用户在白板上的编辑 → `board_snapshots`
- AI/旧接口 → `cards` + `board_edges`
- 已实现 AI→画布单向 merge：`task.actions`/`task.done` 后刷新 cards/edges，`mergeMissingCards` 增量并入 tldraw 并保存 snapshot。
- **未完成**：用户在 tldraw 的编辑不回写 `cards`。后续方向：snapshot 为唯一真相，或 cards↔snapshot 双向同步。

### 6.3 自定义卡片 shape

- `CardShape`（`card-shape-util.tsx`）：`TLBaseShape<'card', { w, h, title, markdown, cardType }>`。
- 继承 `BaseBoxShapeUtil`：自由缩放（拖拽右下角）+ 自动几何/onResize。
- `component` 用 `ReactMarkdown + remarkGfm` 渲染 markdown（标题/列表/粗体/表格/代码块/引用）。
- 旧快照中的 note/geo 卡片经 `migrateLegacyCardShapes` 自动迁移。
- IMAGE 卡仍用 tldraw 原生 image shape。

### 6.4 属性面板（`element-inspector.tsx`）

选中元素后底部弹出：

- 几何图形类型切换（13 种：矩形/椭圆/三角/菱形/五边/六边/八边/星形/云朵/心形/梯形/胶囊等）
- 线型（实线/虚线/点线/手绘）
- 箭头起终点样式（9 种）
- 颜色（12 色）
- 宽高输入
- 旋转 45° / -45°

## 7. 资料上传与解析

- `SourceFile`：storage_key（RustFS）、filename、mime_type、size、text_excerpt、parse_status。
- 上传：`POST /api/boards/{boardId}/files` → RustFS。
- 解析：TXT / Markdown 上传时同步解析为纯文本，写 `text_excerpt`，`parse_status` → `READY`/`FAILED`；其他格式保持 `PENDING`。
- PDF / DOCX / Excel 解析、chunk + embedding + pgvector RAG：**未实现**（后续）。

## 8. AI 任务与 WebSocket

### 8.1 任务类型（`AiTaskType`）

```txt
DOCUMENT_SUMMARY     总结资料
CANVAS_STRUCTURE     整理画布
GENERATE_IMAGE       生成图片
EDIT_IMAGE / GENERATE_COVER / GENERATE_DIAGRAM_ASSET
STUDY_PLAN           学习计划
PRODUCT_REQUIREMENT  产品需求
GENERATE_MINDMAP     生成思维导图
GENERATE_FLOWCHART   生成流程图
```

### 8.2 任务链路

1. `POST /api/boards/{boardId}/ai-tasks` 创建任务（并发限制：每用户 2、每画布写入 1）。
2. `afterCommit` 异步执行（`aiTaskExecutor`）。
3. 步骤落库 `ai_task_steps`，WebSocket 推送事件。
4. 中断：`abort_requested`（DB）+ Redis cancel key，每步前检查。
5. 事件类型：`task.created / step / progress / actions / aborted / failed / done`。

### 8.3 步骤

```txt
加载技能 → 收集上下文 → 规划操作 → 应用操作 → 完成
```

- 加载技能：按任务类型匹配 SKILL.md（见 §10），步骤消息展示技能名与工具范围。
- 收集上下文：已解析资料（截断上限）+ 画布现有卡片（前 20 张）+ 只读工具收集结果。

### 8.4 两段式模型调用（`SpringAiGenerationService`）

1. **收集阶段**：普通对话，允许调用只读工具，自动执行并回传结果（解决 json_schema 模式下工具被禁用的问题）。
2. **规划阶段**：`.entity(BoardActionPlan)` 结构化输出，注入已收集上下文。
3. **重试回路**：`BoardActionValidator` 校验（空动作/标题空/自连/重复连线），失败把错误反馈模型重试 1 次，再失败任务 FAILED 不落库。

### 8.5 只读工具（`BoardReadTools`）

经 `@Tool` 注册给模型：

- `get_board_context`：画布标题 + 卡片摘要
- `get_card_content`：指定卡片完整正文
- `list_source_files`：画布资料列表

工具调用结果写入 `ai_tool_calls` 审计（taskId 经 `toolContext` 注入）。`search_document_chunks` 待 RAG 落地。

## 9. AI 输出协议（`BoardAction`）

AI 只提案，后端统一校验落库：

```java
sealed interface BoardAction
  permits CreateCard, UpdateCard, Connect, CreateGroup, DeleteCard
```

| 动作 | 字段 | 说明 |
| --- | --- | --- |
| `create_card` | id(可选)/title/markdown/cardType/x/y/width/height | 新建卡片；`id` 供本计划 connect 引用新卡 |
| `update_card` | cardId/title/markdown | 更新卡片 |
| `connect` | fromCardId/toCardId/label | 画连线；先按本计划新卡临时 id 解析，再按 UUID 解析 |
| `create_group` | title/cardIds | 识别但不落库（无分组表） |
| `delete_card` | cardId | 删除卡片（连带连线） |

`applyActions` 两遍执行：先建所有卡并记录 临时id→UUID 映射，再处理连线/更新/删除，支持结构图（一次多卡+连线）。

## 10. Skill 系统

- 内置 5 个 Skill（`backend/src/main/resources/ai-skills/*/SKILL.md`）：document-summary、canvas-structure、image-generation、study-plan、product-requirement。
- `SkillLoader` 解析 YAML frontmatter（slug/name/task_types/allowed_tools）+ Markdown 正文。
- `SkillDefinition` 注入 `AiTaskContext`，生成服务将其正文与工具范围放入 system prompt。
- 结构图任务（GENERATE_MINDMAP/FLOWCHART）有专用 `structurePrompt` 约束（中心+分支 / 开始-步骤-结束 + connect 串联）。
- Skill 存库 / 用户自定义：后续扩展。

## 11. 模板（前端 `templates.ts`）

- `mindmap` 思维导图：中心主题 + 4 分支
- `study-plan` 学习计划：阶段 → 步骤
- `prd` 产品需求：目标/用户/范围/里程碑
- `flowchart` 流程图：开始/处理/判断/结束
- `build(rootTitle)` 返回 `WhiteboardSpec`，落库走 `createCard` + `createEdge`，经 `mergeMissingCards` 进 tldraw。

## 12. 自动布局（`layout.ts`）

- `autoLayout(spec)`：按连线切分连通分量 → 分量内 BFS 分层 → 孤立节点 3 列网格 → 分量按列拼接。
- 编辑器内 `autoLayoutTldraw`：对 tldraw 元素分层重排（"整理"按钮）。

## 13. 数据表一览

| 表 | 迁移 | 实体 | 说明 |
| --- | --- | --- | --- |
| users | V1 | ✅ | 演示用户自动创建 |
| boards | V1 | ✅ | 画布 |
| board_snapshots | V1 | ✅ | tldraw 快照 |
| cards | V1 | ✅ | 卡片 |
| card_source_files | V1 | ✅ | 卡片-资料关联 |
| source_files | V1 | ✅ | 上传资料（含解析状态） |
| document_chunks | V1 | ✅ | 预留 RAG chunk |
| ai_tasks | V1 | ✅ | AI 任务状态机 |
| ai_task_steps | V1 | ✅ | 任务步骤 |
| ai_tool_calls | V1 | ✅ | 工具调用审计 |
| assets | V1 | ✅ | 预留资产 |
| share_links | V1 | ✅ | 预留分享 |
| board_edges | V2 | ✅ | 卡片连线 |

## 14. API 一览

```text
GET  /api/boards                 列表
POST /api/boards                 创建
GET  /api/boards/{boardId}       详情
PATCH /api/boards/{boardId}      重命名

GET  /api/boards/{boardId}/snapshot      读快照
POST /api/boards/{boardId}/snapshot      存快照

GET  /api/boards/{boardId}/cards         列表
POST /api/boards/{boardId}/cards         创建
PATCH /api/cards/{cardId}                更新

GET  /api/boards/{boardId}/edges         列表
POST /api/boards/{boardId}/edges         创建

POST /api/boards/{boardId}/files         上传
GET  /api/boards/{boardId}/files         列表
GET  /api/boards/{boardId}/files/{fileId}/content   读内容

POST /api/boards/{boardId}/ai-tasks      创建任务
GET  /api/boards/{boardId}/ai-tasks      任务列表
GET  /api/ai-tasks/{taskId}              任务详情
POST /api/ai-tasks/{taskId}/abort        中断

WS   /ws（SockJS）
TOPIC /topic/boards/{boardId}/tasks
```

## 15. 当前可演示能力

1. 工作台创建/打开画布。
2. tldraw 白板：卡片（自由缩放 + Markdown 渲染）、文本、12 种图形、线条、箭头、图片。
3. 属性面板：图形/线型/箭头/颜色/尺寸/旋转。
4. 快照防抖保存，刷新恢复；旧卡自动迁移。
5. 上传 TXT/Markdown → 自动解析，作为 AI 上下文。
6. AI 任务（真实模型）：总结资料、整理画布、生成思维导图/流程图；步骤/进度实时推送，可中断。
7. AI 只读工具收集上下文（`ai_tool_calls` 审计）。
8. 模板：思维导图/学习计划/PRD/流程图。
9. 自动整理（分层布局）。
10. AI 删除指定卡片。

## 16. 已知限制与后续路线

### 已确认限制

- 认证已接入 JWT，但保留演示头回退（未强制登录，refreshToken 未强校验）。
- 卡片 markdown 双击编辑未接（当前只读渲染 + 手动新建）。
- PDF/DOCX/Excel 解析、chunk + embedding + pgvector RAG 未实现。
- 画布双状态源（snapshot vs cards）未彻底统一。
- AI 生图、分享链接、导出未实现。
- 测试基本空白。

### 后续优先级

1. `search_document_chunks`：先做 chunk + embedding + pgvector 写入（资料可检索）。
2. 卡片双击编辑 markdown 正文（写回 `cards`）。
3. 结构图生成后自动布局整理（autoLayout 兜底）。
4. 模板参数化。
5. 真实登录（AuthController + Security）。
6. AI 生图 + assets 落库。
7. 分享链接只读页。
8. 导出 PNG / Markdown。
