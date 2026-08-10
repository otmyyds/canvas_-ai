# 项目进度报告

> 对照文档：`project_plan.md`  
> 盘点日期：2026-08-06（晚间复盘）  
> 项目名称：单人 AI 文档画布（Doc Canvas）

---

## 1. 总体结论

当前项目处于 **MVP 骨架已跑通、真实 AI 主链路已接通、多数计划功能仍为半成品** 的阶段。

已具备：

- 前后端工程骨架与本地启动脚本
- Docker Compose 基础设施（PostgreSQL + pgvector / Redis / RustFS）
- 私有画布 CRUD、卡片、连线、快照
- 文件上传到 RustFS 与内容读取
- AI 异步任务编排、步骤落库、WebSocket 进度推送、任务中断
- Mock AI / Spring AI 双实现切换
- **真实 Spring AI 调用已联调通过**（`AI_PROVIDER=spring-ai` + 系统环境变量 `GPT_API_KEY` + 模型 `gpt-5.4`）
- 前端 tldraw 白板编辑器接入与 snapshot 防抖保存
- AI 生成卡片可增量合并进 tldraw 并保存进 snapshot
- 用户可见文案与后端错误中文化

尚未完成：

- 真实登录认证与权限体系
- 文件解析 / chunk / embedding / RAG
- AI 工具调用闭环与 Skill 真正执行
- 图片生成与 assets 落库
- BlockNote 富文本详情编辑
- 分享链接与导出
- 测试体系与结构化输出强校验
- 画布双状态源彻底统一（用户编辑仍不回写 cards）

粗估整体完成度：**约 42%–48%（相对完整 MVP）**。  
若只看“可本地演示的纵切骨架 + 真实 AI 出卡”，完成度约 **60%–65%**。

---

## 2. 仓库结构现状

```text
picture_create/
├── backend/                 # Spring Boot 3.5 + Java 21
├── frontend/                # Next.js 15 App Router + React 19
├── infra/docker-compose.yml # postgres(pgvector) + redis + rustfs
├── docs/architecture.md
├── project_plan.md
├── processing.md            # 本进度文档
├── README.md
├── run-backend.ps1
├── run-frontend.ps1
├── .env / .env.example
└── logs/ / tmp/
```

### 技术栈落地情况

| 计划技术 | 状态 | 说明 |
| --- | --- | --- |
| Next.js App Router | ✅ 已接入 | `/`、`/app`、`/board/[boardId]` |
| React + TypeScript | ✅ 已接入 | React 19 |
| Tailwind CSS | ✅ 已接入 | 自定义主题色 |
| shadcn/ui | ⚠️ 部分替代 | 使用自写 `components/ui.tsx`，未正式 init shadcn |
| zustand | ✅ 已接入 | `use-board-store` |
| tldraw | ✅ 已接入 | 主编辑器为 tldraw |
| BlockNote | ⚠️ 仅依赖 | package.json 已装，业务未接入编辑器 |
| Spring Boot 3 | ✅ 已接入 | 3.5.15 |
| Spring Security | ⚠️ 空壳 | 全部 `permitAll`，无登录 |
| Spring WebSocket/STOMP | ✅ 已接入 | SockJS + `/topic/boards/{id}/tasks` |
| Spring Data JPA + Flyway | ✅ 已接入 | V1/V2 迁移 |
| PostgreSQL + pgvector | ✅ 基建已就绪 | 表有 embedding 列，业务未写向量 |
| Redis | ✅ 已接入 | 用于任务取消标记 |
| Spring AI OpenAI | ✅ 已联调 | 默认 `AI_PROVIDER=spring-ai`，真实模型可用 |
| Resilience4j | ❌ 未接入 | pom 使用 spring-retry |
| RustFS / S3 SDK | ✅ 已接入 | 上传/读取可用 |

---

## 3. 按计划阶段对照

### 阶段 1：项目初始化 — ✅ 基本完成

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| 创建前端 Next.js 项目 | ✅ | `frontend/` |
| 创建后端 Spring Boot 项目 | ✅ | `backend/pom.xml` |
| Docker Compose：PG / Redis / RustFS | ✅ | `infra/docker-compose.yml` |
| 前端 Tailwind / UI | ✅ | Tailwind + 自研 UI 组件 |
| 后端 DB / Redis / 存储 / Spring AI 配置 | ✅ | `application.yml`、`RustFsObjectStorageService` |

备注：

- 本地启动脚本：`run-backend.ps1`、`run-frontend.ps1`
- `run-backend.ps1` 会提升 User/Machine 级 `GPT_API_KEY` 等到进程，并打印 provider/baseUrl/key 状态（不打印密钥）
- 默认已切到 `AI_PROVIDER=spring-ai`；需要纯离线演示可改回 `mock`

---

### 阶段 2：认证与权限 — ⚠️ 演示级占位

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 用户注册/登录/退出 | ❌ | 无 AuthController，无 `/api/auth/*` |
| Spring Security 保护 API | ❌ | 全接口 `permitAll` |
| owner 权限模型 | ⚠️ | 服务层按 `owner_id` 过滤，但用户靠请求头伪造 |
| 私人内容隔离 | ⚠️ | 演示用户 `demo@example.com`，无真实身份体系 |

当前实现：

- `CurrentUserService` 从请求头读取 `X-User-Email` / `X-User-Name`
- 没有则默认 `demo@example.com` / `演示用户`
- 前端 `api.ts` 对中文名做 `encodeURIComponent`，后端 `URLDecoder` 还原（已修复 ISO-8859-1 请求头崩溃）

结论：能支撑单人本地开发，**不可作为真实安全边界**。

---

### 阶段 3：画布编辑器 — ✅ 主体完成（仍有双轨痕迹）

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 接入 tldraw | ✅ | `WhiteboardEditor` 为主编辑器 |
| 自定义卡片 shape | ⚠️ | 主要用 tldraw 内建 note/geo/text/image/arrow，未做业务 custom shape |
| 创建/拖拽/缩放/删除/连接线 | ✅ | tldraw 原生交互 + 工具栏 |
| 防抖保存 snapshot | ✅ | store.listen + 1s debounce POST snapshot |
| 刷新后恢复 snapshot | ✅ | 优先加载 tldraw snapshot，失败则 legacy cards/edges |
| AI 卡片同步到画布 | ⚠️ 阶段性 | snapshot 载入后 mergeMissingCards；task.done 后 canvasRevision 增量同步 |

相关前端文件：

- `frontend/src/components/whiteboard/whiteboard-editor.tsx`
- `frontend/src/lib/whiteboard/schema.ts`
- `frontend/src/lib/whiteboard/tldraw-adapter.ts`
- 旧 DOM 画布仍保留：`frontend/src/components/board/canvas-view.tsx`（当前主路径未用）

相关后端：

- `SnapshotController` + `BoardSnapshotService`
- `BoardEdgeController` + `BoardEdgeService`
- `CardController` + `CardService`

已知缺口：

1. tldraw 画布变更主要落在 `board_snapshots`，**不会自动同步回 `cards` / `board_edges` 表**。
2. AI 任务应用 `create_card` 写的是 `cards` 表；前端已做增量 merge，但双写模型仍在。
3. 自定义业务 shape、语义节点、分组组件尚未做。

---

### 阶段 4：卡片详情编辑 — ❌ 基本未做

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 点击卡片打开右侧详情 | ❌ | 右侧是 AI 任务面板，不是卡片详情 |
| BlockNote 编辑正文 | ❌ | 依赖已安装，无编辑组件 |
| 卡片正文保存后端 | ⚠️ | 有 `PATCH /api/cards/{id}` 能力，前端未接详情流 |
| 摘要同步到画布 | ❌ | 无详情编辑闭环 |

---

### 阶段 5：文件上传与解析 — ⚠️ 仅上传层完成

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 前端拖拽/选择上传 | ✅ | `SourcePanel` + `api.uploadFile` |
| 原文件存 RustFS | ✅ | `FileService` + `RustFsObjectStorageService` |
| 读取文件内容 | ✅ | `GET /api/boards/{boardId}/files/{fileId}/content` |
| 解析 PDF/DOCX/TXT/MD/Excel | ❌ | 无解析器；`parse_status` 停在 `PENDING` |
| 文本切 chunk | ❌ | `document_chunks` 表/实体存在，无 Repository 与写入服务 |
| embedding + pgvector | ❌ | 表字段有 `vector(1536)`，无 embedding 业务 |
| 解析状态推进 | ❌ | 上传后 `parse_status` 停留在初始态 |

结论：资料库可存可看列表，**不能被 AI 真正检索利用**。真实 AI 目前只吃用户 prompt，不吃资料正文。

---

### 阶段 6：AI 任务与 WebSocket — ✅ 主链路可用（能力仍浅）

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 创建 AI 任务 | ✅ | `POST /api/boards/{boardId}/ai-tasks` |
| 任务列表/详情 | ✅ | list + get |
| 异步执行 | ✅ | `aiTaskExecutor` afterCommit |
| 步骤落库 | ✅ | `ai_task_steps` |
| WebSocket 推送 | ✅ | `task.created/step/progress/actions/done/failed/aborted` |
| 中断任务 | ✅ | DB `abort_requested` + Redis cancel key |
| 并发限制 | ✅ | 每用户 / 每画布写入型任务限制 |
| 任务完成后应用动作 | ⚠️ | 仅支持 `create_card` |
| REST 恢复任务状态 | ✅ | 前端事件后重新 listTasks |
| 真实模型调用 | ✅ | Spring AI ChatClient 已联调通过 |

AI 实现现状：

| Provider | 状态 | 行为 |
| --- | --- | --- |
| `mock` | ✅ | 固定生成 1 张 SUMMARY 卡片 |
| `spring-ai`（当前默认） | ✅ 已联调 | 调 ChatClient 拿 Markdown，再包成 1 张 SUMMARY 卡片 |

当前 AI 配置（`.env` / `application.yml`）：

- `AI_PROVIDER=spring-ai`
- `GPT_API_KEY` 来自系统环境变量（Machine/User），`.env` 留空不覆盖
- `GPT_BASE_URL=https://apinebula.ai`（**不要带 `/v1`**，Spring AI 会拼 `/v1/chat/completions`）
- `AI_MODEL_PLANNER=gpt-5.4`

本轮联调结论：

1. 原先 `AI_PROVIDER=mock` 导致“看起来有 AI、实际不调模型”
2. 原先 `GPT_BASE_URL=.../v1` 会拼成 `/v1/v1/chat/completions`
3. 原先默认模型 `gpt-4.1` 在当前网关返回 `model_not_found`
4. 修正后真实任务可 `SUCCEEDED`，并写入真实模型正文卡片

未完成的关键能力：

- Skill 加载与按 skill 编排（资源目录有 5 个 SKILL.md，运行时未真正驱动）
- 工具调用：`get_board_context` / `search_document_chunks` / `propose_*` 等
- `ai_tool_calls` 表有实体与仓库，业务几乎未写入
- 结构化 `BoardAction[]` 强校验（Java sealed DTO + 前端 Zod 二次校验）
- `update_card` / `connect` / `create_group` 应用
- 失败重试、schema 修复回路、loop 检测
- 图片任务真实流程
- 注入画布卡片 / 资料正文上下文

前端任务面板已支持：

- 总结资料 / 整理画布 / 生成图片 三类入口
- 步骤进度展示
- 中断按钮
- 连接状态展示

---

### 阶段 7：AI 生图 — ❌ 基本未做

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| image-generation Skill | ⚠️ 仅文档 | `ai-skills/image-generation/SKILL.md` 存在 |
| `ImagePromptSpec` | ❌ | 无 DTO/校验 |
| 调用图片模型 | ❌ | 无 image client |
| 图片写 RustFS + assets | ⚠️ 半有 | `assets` 表与实体存在，无生成链路 |
| 生成图片卡片 | ❌ | 图片类任务当前也只生成文字提示词卡 |
| 失败保留提示词卡 | ❌ | 无专门兜底 |

前端可手工上传图片到 tldraw，并经 asset upload 走 RustFS；这是**用户上传图片**，不是 **AI 生图**。

---

### 阶段 8：分享与导出 — ❌ 未做

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 创建只读分享链接 | ❌ | `share_links` 表存在，无 API/Service |
| `/share/[token]` 页面 | ❌ | 前端无路由 |
| 导出 PNG | ❌ | 未实现 |
| 导出 Markdown | ❌ | 未实现 |

---

## 4. 数据模型落地情况

| 表 | 迁移 | 实体 | Repository | 业务读写 |
| --- | --- | --- | --- | --- |
| users | ✅ | ✅ | ✅ | ✅ 演示用户自动创建 |
| boards | ✅ | ✅ | ✅ | ✅ |
| board_snapshots | ✅ | ✅ | ✅ | ✅ |
| cards | ✅ | ✅ | ✅ | ✅ |
| board_edges | ✅ V2 | ✅ | ✅ | ✅ |
| source_files | ✅ | ✅ | ✅ | ✅ 上传/列表/读内容；解析字段未推进 |
| document_chunks | ✅ | ✅ | ❌ | ❌ |
| ai_tasks | ✅ | ✅ | ✅ | ✅ |
| ai_task_steps | ✅ | ✅ | ✅ | ✅ |
| ai_tool_calls | ✅ | ✅ | ✅ | ⚠️ 基本未用 |
| assets | ✅ | ✅ | ❌ | ❌ |
| share_links | ✅ | ✅ | ❌ | ❌ |
| card_source_files | ✅ 关联表 | ⚠️ 未完整用 | ❌ | ❌ |

说明：schema 比业务更超前，很多表是“先建后用”。

---

## 5. API 落地情况

### 已实现

```text
GET    /api/boards
POST   /api/boards
GET    /api/boards/{boardId}
PATCH  /api/boards/{boardId}

GET    /api/boards/{boardId}/snapshot
POST   /api/boards/{boardId}/snapshot

GET    /api/boards/{boardId}/cards
POST   /api/boards/{boardId}/cards
PATCH  /api/cards/{cardId}

GET    /api/boards/{boardId}/edges
POST   /api/boards/{boardId}/edges

POST   /api/boards/{boardId}/files
GET    /api/boards/{boardId}/files
GET    /api/boards/{boardId}/files/{fileId}/content

POST   /api/boards/{boardId}/ai-tasks
GET    /api/boards/{boardId}/ai-tasks
GET    /api/ai-tasks/{taskId}
POST   /api/ai-tasks/{taskId}/abort

WS     /ws  (SockJS)
TOPIC  /topic/boards/{boardId}/tasks
```

### 计划中但未实现

```text
POST /api/auth/login|logout
GET  /api/auth/me
DELETE /api/boards/{boardId}
DELETE /api/cards/{cardId}
GET  /api/files/{fileId}
POST /api/boards/{boardId}/share-links
GET  /api/share/{token}
DELETE /api/share-links/{id}
白板 elements/connectors/whiteboard-spec 系列 API
AI 预览确认 apply/reject API
文件解析触发 / 重试 API
```

---

## 6. 前端页面与模块

| 页面/模块 | 状态 | 说明 |
| --- | --- | --- |
| `/` | ⚠️ 简入口 | 存在，产品落地页很轻 |
| `/app` 工作台 | ✅ | 列表/新建画布；中文请求头已修复 |
| `/board/[boardId]` | ✅ | 主编辑器 |
| `/share/[token]` | ❌ | 无 |
| 左侧资料库 | ✅ | `SourcePanel` |
| 右侧 AI 任务面板 | ✅ | `TaskPanel` |
| 右侧卡片详情面板 | ❌ | 无 |
| tldraw 白板 | ✅ | 主路径 |
| 旧 DOM CanvasView | ⚠️ 遗留 | 代码仍在，主路径未用 |
| WhiteboardSpec schema | ✅ 前端类型层 | 未接到 AI 生成/应用闭环 |
| BlockNote | ❌ 未接 UI |  |
| shadcn 组件库 | ⚠️ 自研替代 |  |

---

## 7. AI 内核对照（计划第 9 章）

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| AI 只提案、后端落库 | ⚠️ 原则有、实现浅 | mock/spring-ai 返回 actions，后端 apply |
| Skill 资源文件 | ✅ 5 个内置 | document-summary / canvas-structure / image-generation / study-plan / product-requirement |
| Skill 运行时加载执行 | ❌ | 任务步骤文案写“加载技能”，未真正解析 SKILL.md |
| 只读工具 | ❌ | 未实现 |
| 提案工具 | ❌ | 未实现 |
| 受控写入工具 | ⚠️ 硬编码 apply | 仅 `create_card` |
| 模型策略切换 | ⚠️ 配置项有 | 无 high/cheap/image 路由 |
| 异步任务状态机 | ✅ | created/queued/running/succeeded/failed/aborted |
| 取消协作 | ✅ | DB + Redis |
| 重试/退避 | ❌ | 未形成策略实现 |
| 防死循环 | ⚠️ 部分 | 有并发限制与 max 配置，无 loop_key 检测 |
| 结构化输出强校验 | ❌ | Map 弱类型为主 |
| RAG | ❌ | 无 |
| 真实模型联调 | ✅ | `spring-ai` + `gpt-5.4` 已成功出卡 |

---

## 8. 组件白板升级路线（计划第 15 章）进度

| P0 任务 | 状态 |
| --- | --- |
| 接入 tldraw 基础编辑器 | ✅ |
| 定义 WhiteboardSpec TS schema | ✅ |
| Spec 与 tldraw 单向渲染 | ⚠️ 仅有 adapter 雏形，主路径直接用 tldraw snapshot |
| 元素保存与恢复 | ✅（tldraw snapshot） |
| 不回退卡片/连线/图片能力 | ⚠️ 有 legacy 兼容，但双状态源风险存在 |
| AI 新卡进入 tldraw | ⚠️ 已做 mergeMissingCards + canvasRevision |

| P1 任务 | 状态 |
| --- | --- |
| 左侧图形库 | ⚠️ 工具栏有限按钮，非完整 palette |
| 右侧属性面板 | ❌ |
| AI 白板预览任务 | ❌ |
| 预览确认后应用 | ❌ |

| P2/P3 | 状态 |
| --- | --- |
| 自动布局 / 图片生成节点 / 导出 PNG / 模板 / 版本历史 / 分享 | ❌ |

---

## 9. 测试进度

| 类型 | 状态 |
| --- | --- |
| 后端单元/集成测试 | ❌ 几乎空（`src/test` 无有效用例） |
| 前端测试 | ❌ 无 |
| E2E | ❌ 无 |

计划中的权限、中断不写库、loop 检测、分享过期等测试均未落地。  
本轮仅做过手工/脚本联调：健康检查、画布列表、真实 AI 任务成功出卡。

---

## 10. 当前可演示能力（已打通纵切）

1. 打开工作台，创建私有画布  
2. 进入画布，使用 tldraw 添加卡片/文本/图形/线条/箭头/图片  
3. 画布变更防抖保存 snapshot，刷新可恢复  
4. 左侧上传资料到 RustFS  
5. 右侧创建 AI 任务（默认真实 spring-ai；可改 mock）  
6. WebSocket 实时看步骤与进度  
7. 中断任务  
8. 任务成功后后端写入 `create_card` 到 `cards` 表  
9. 前端把 AI 新卡 merge 进 tldraw，并触发 snapshot 保存  
10. 真实模型返回的 Markdown 正文会出现在画布卡片上  

本地地址：

- 前端：http://localhost:3000/app  
- 后端健康：http://localhost:8080/actuator/health  

---

## 11. 关键风险与技术债

1. **双状态源**  
   - 用户编辑主要写 `board_snapshots`  
   - AI/旧接口主要写 `cards` + `board_edges`  
   - 已有 AI→画布单向 merge，但仍未双向同步

2. **认证是演示头**  
   - 任何人都可伪造 `X-User-Email`  
   - 不适合多用户或公网暴露

3. **AI 能力表面完整、内核仍浅**  
   - 有任务面板、步骤、中断、真实模型  
   - 无 RAG、无工具循环、无严格 schema、无生图、无 Skill 执行

4. **Skill 与工具表“文档先行”**  
   - 资源与表已建，执行器未接

5. **BlockNote / shadcn 依赖与实现脱节**  
   - 依赖在，产品路径未用

6. **测试缺失**  
   - 重构双状态源、权限、中断语义时风险高

7. **模型网关依赖**  
   - 当前依赖 `apinebula.ai` 与 `GPT_API_KEY`  
   - 模型名需与网关可用列表一致（当前可用 `gpt-5.4` 等）

---

## 12. 建议下一步（按优先级）

### P0 — 先把主路径做稳

1. **统一画布状态源**  
   - 推荐：tldraw snapshot 为唯一真相，AI 应用动作后同步 patch snapshot  
   - 或继续完善 cards↔snapshot 双向同步

2. **AI 动作应用与前端刷新闭环**  
   - 补 `connect` 动作  
   - 支持多卡布局，不只单卡

3. **Spring AI 结构化输出**  
   - 用 JSON Schema / DTO 约束 `BoardAction[]`  
   - 校验失败重试一次

### P1 — 补齐 MVP 核心价值

4. 文件解析（先 TXT/MD，再 DOCX/PDF）  
5. chunk + embedding + `search_document_chunks`  
6. AI 生成时注入画布卡片与资料正文  
7. 真正加载 Skill 并限制工具范围  
8. 卡片详情面板 + BlockNote  
9. 最小登录（可先 session/email magic 或本地账号）

### P2 — 计划中的增强

10. AI 生图 + assets  
11. 分享链接只读页  
12. 导出 PNG  
13. 测试补齐（权限、中断、动作校验、上传失败）

### P3 — 白板升级

14. 自定义 shape / 属性面板  
15. WhiteboardSpec 预览确认流  
16. 自动布局与模板

---

## 13. 进度总表（一页版）

| 计划阶段 | 完成度 | 结论 |
| --- | --- | --- |
| 1. 项目初始化 | 95% | 可本地启动 |
| 2. 认证与权限 | 20% | 演示用户头占位（含中文头编码修复） |
| 3. 画布编辑器 | 80% | tldraw + snapshot 可用，AI 卡可 merge |
| 4. 卡片详情编辑 | 10% | 后端 update 有，前端详情无 |
| 5. 文件上传与解析 | 35% | 上传有，解析/RAG 无 |
| 6. AI 任务与 WebSocket | 78% | 编排/进度/中断/真实模型可用，智能仍浅 |
| 7. AI 生图 | 10% | 仅类型与 skill 文档 |
| 8. 分享与导出 | 5% | 仅表结构 |
| 测试 | 2% | 仅手工联调，无自动化 |
| 白板升级路线（15 章） | 30% | P0 大部分完成 |

**综合：MVP 约 42%–48%；可演示骨架 + 真实 AI 约 60%–65%。**

---

## 14. 本次盘点依据的主要代码

### 后端

- `backend/pom.xml`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__init.sql`
- `backend/src/main/resources/db/migration/V2__board_edges.sql`
- `backend/src/main/java/.../service/AiTaskService.java`
- `backend/src/main/java/.../service/ai/MockAiGenerationService.java`
- `backend/src/main/java/.../service/ai/SpringAiGenerationService.java`
- `backend/src/main/java/.../service/FileService.java`
- `backend/src/main/java/.../service/CurrentUserService.java`
- `backend/src/main/java/.../config/SecurityConfig.java`
- `backend/src/main/java/.../storage/RustFsObjectStorageService.java`
- `run-backend.ps1`

### 前端

- `frontend/package.json`
- `frontend/src/app/app/page.tsx`
- `frontend/src/app/board/[boardId]/page.tsx`
- `frontend/src/components/whiteboard/whiteboard-editor.tsx`
- `frontend/src/components/board/task-panel.tsx`
- `frontend/src/lib/api.ts`
- `frontend/src/lib/types.ts`
- `frontend/src/lib/use-task-stream.ts`
- `frontend/src/lib/whiteboard/schema.ts`

### 基建与文档

- `infra/docker-compose.yml`
- `README.md`
- `docs/architecture.md`
- `project_plan.md`
- `.env` / `.env.example`

---

## 15. 更新约定

后续开发建议每次完成一个可验证里程碑后更新本文件：

1. 改“阶段对照”中对应行状态  
2. 在“可演示能力”补充验收步骤  
3. 若引入新状态源/新表/新 API，同步更新风险与 API 清单  

最后一次完整盘点：2026-08-06（晚间复盘）。

---

## 16. 本次迭代记录（2026-08-06）

### 16.1 中文化

已完成用户可见文案中文化：

- 前端演示用户名：`Demo User` → `演示用户`
- 前端错误映射补齐（网络失败、校验失败、HTTP 状态）
- 后端校验注解 message 中文化
- 后端 `ErrorHandler` 统一中文错误返回
- AI Skill 文档中文化
- 架构说明文档中文化
- 上传默认文件名、任务进度文案统一中文

### 16.2 P0：统一画布状态源（阶段性）

已实现：

1. AI 任务 `task.actions` / `task.done` 后刷新 cards/edges，并提升 `canvasRevision`
2. tldraw 载入 snapshot 后，**合并**后端 cards 中尚未出现在画布上的卡片
3. 运行中 AI 新生成卡片会**增量同步**到 tldraw，并触发 snapshot 保存
4. 快照保存改走统一 `api.saveSnapshot`，不再硬编码英文用户头

仍未彻底完成：

- 用户在 tldraw 中的编辑仍不会回写 `cards` 表
- AI 只写 `cards`，用户编辑只写 `board_snapshots` 的双写模型还在
- 下一步可继续做：snapshot 为唯一真相，或 cards↔snapshot 双向同步

### 16.3 请求头中文崩溃修复

问题：

- 浏览器 `fetch` 不允许 header 出现非 ISO-8859-1 字符
- `X-User-Name: 演示用户` 导致工作台直接报错，前后端“看起来没连上”

修复：

- 前端：`encodeURIComponent("演示用户")`
- 后端：`CurrentUserService` 对 header 值做 `URLDecoder`

### 16.4 真实 AI 联调

问题与修复：

1. `.env` 原默认 `AI_PROVIDER=mock` → 改为 `spring-ai`
2. `GPT_BASE_URL` 原带 `/v1` → 改为 `https://apinebula.ai`，避免 `/v1/v1/...`
3. 默认模型原 `gpt-4.1` 在当前网关不存在 → 改为 `gpt-5.4`
4. `run-backend.ps1` 提升系统环境变量 `GPT_API_KEY`，`.env` 空值不覆盖密钥
5. `SpringAiGenerationService` 补中文错误包装，失败时任务可标 FAILED

验收：

- 启动日志可见：`AI config: provider=spring-ai baseUrl=https://apinebula.ai apiKey=set(len=...)`
- 创建 `DOCUMENT_SUMMARY` 任务后可到 `SUCCEEDED`
- 画布 cards 中出现真实模型生成的 Markdown 摘要卡

### 16.5 下一步建议开工项

按价值排序：

1. TXT/MD 文件解析 + `parse_status` 推进  
2. AI 任务注入已解析资料与当前画布卡片  
3. Skill 运行时加载  
4. 结构化 BoardAction 校验  
5. 卡片详情 / BlockNote  

---

## 18. 本轮迭代记录（2026-08-06 续）

### 18.1 TXT/MD 文件解析 + 资料注入 AI

已完成：

- `FileService.upload`：识别 `.txt/.md/.markdown` 及 text/markdown MIME，读 UTF-8 去 BOM，写入 `text_excerpt`，`parse_status` 推进到 `READY`；失败置 `FAILED`；其他格式保持 `PENDING`
- `SourceFile` 新增 `markParsed` / `markParseFailed`
- `SourceFileRepository` 新增按 `READY` 查询
- `AiTaskContext` 增加 `sources`（文件名 + 内容）
- `AiTaskService.generate` 注入已解析资料，单文件 6000 字符、单任务累计 20000 字符截断
- `SpringAiGenerationService` / `MockAiGenerationService` 在 prompt 中附资料内容，并要求“优先依据资料、不编造事实”
- 前端 `SourcePanel` 提示改为“TXT/Markdown 自动解析”

验收：

- 上传 `parse-test.txt` → `parseStatus: READY`，`textExcerpt` 有值
- `DOCUMENT_SUMMARY` 真实任务 SUCCEEDED，新卡片正文明确引用资料内容（Doc Canvas 能力/技术栈）

### 18.2 画布卡片上下文 + 多动作协议

已完成：

- `AiTaskContext` 增加 `cards`（现有卡片 id/标题/类型/正文，最多 20 张）
- `AiTaskService.generate` 一并注入当前画布卡片
- `applyActions` 支持三类动作：
  - `create_card`：新建卡片（原有）
  - `update_card`：按 `cardId` 更新标题/正文（校验属于当前画布）
  - `connect`：`fromCardId` → `toCardId` 建连线（校验归属与不指向自身）
  - 未知动作忽略，不阻塞任务
- `AiTaskService` 注入 `BoardEdgeRepository`
- `SpringAiGenerationService` / `MockAiGenerationService` 的 prompt 附现有卡片列表

验收：

- `CANVAS_STRUCTURE` 真实任务 SUCCEEDED，新生成的“画布概览”卡准确识别画布主题与技术栈（证明模型感知了画布已有内容）
- 连线表结构已支持 `connect`，等待模型在多动作任务中返回

### 18.3 本轮状态小结

| 能力 | 状态 |
| --- | --- |
| TXT/MD 解析与状态推进 | ✅ 已验证 |
| 资料正文注入 AI prompt | ✅ 已验证 |
| 画布现有卡片注入 AI prompt | ✅ 已验证 |
| 多动作应用 create_card / update_card / connect | ✅ 已实现待更多动作任务验证 |

下一步建议：

1. 让 spring-ai 返回结构化 `BoardAction[]`（JSON Schema 强约束），使多动作真正可用
2. Skill 运行时加载与工具范围限制
3. 卡片详情 / BlockNote
4. 自动布局与模板

---

## 19. 本轮迭代记录（2026-08-06 再续）：结构化 BoardAction 协议

### 19.1 强类型动作协议

按计划第 8 章落地 `BoardAction`：

- 新增 `BoardAction` sealed interface（`backend/.../service/ai/BoardAction.java`）：
  - `CreateCard`：title/markdown/cardType/x/y/width/height/sourceFileIds
  - `UpdateCard`：cardId/title/markdown
  - `Connect`：fromCardId/toCardId/label
  - `CreateGroup`：title/cardIds（当前 MVP 无分组表，识别不落库）
  - `BoardActionPlan` 包装 List，供 Spring AI 结构化反序列化
- `BoardActionResult` 从 `List<Map>` 改为 `List<BoardAction>`
- `TaskEvent.actions` 推送强类型 `List<BoardAction>`
- `AiTaskService.applyActions` 用 pattern matching 分发，逐一校验归属/自连后落库
- 前端 `types.ts` 同步定义 `BoardAction` 联合类型

### 19.2 Spring AI 结构化输出

- `SpringAiGenerationService` 改用 `.call().entity(BoardActionPlan.class)`，要求模型输出结构化动作，不再让模型自由写 Markdown
- system 约束：cardId 必须使用上下文里的真实 id、cardType 取值受限、动作 1-3 个
- `MockAiGenerationService` 返回强类型动作；画布有卡片时附带一条 connect 演示多动作

### 19.3 验收结果

真实 `CANVAS_STRUCTURE` 任务：

- 模型返回 3 个动作（actionCount=3）
- 新生成 1 张“画布总览”卡
- `connect` 真实落库 2 条连线（e030f820→210d1b38 “概览”、e030f820→36d390f7 “价值”），均引用画布内真实卡片 id

结论：**AI 只提案、后端校验落库、WebSocket 推强类型动作**的闭环已打通。`update_card`/`create_group` 协议就绪，等待任务触发。

### 19.4 下一步

1. Skill 运行时加载（按任务类型选 SKILL.md，限制工具范围）
2. 失败重试与 schema 修复回路
3. 卡片详情 / BlockNote
4. 自动布局与模板

---

## 20. 本轮迭代记录（2026-08-06 三续）：Skill 运行时加载

### 20.1 完成内容

- 新增 `SkillDefinition`（slug/name/version/description/taskTypes/allowedTools/body），对应 SKILL.md 的 YAML frontmatter 与正文
- 新增 `SkillLoader`：
  - 扫描 `classpath:ai-skills/*/SKILL.md`
  - 用 SnakeYAML 解析 frontmatter，截取 Markdown 正文
  - 提供 `findForTask(taskType)` 按任务类型匹配、`all()` 枚举
  - 资源缺失 / frontmatter 非法时静默跳过，不阻断启动
- `AiTaskContext` 增加 `skill` 字段
- `AiTaskService`：
  - "加载技能"步骤改为真实查找 Skill，步骤消息展示技能名与允许工具范围
  - `generate` 将 Skill 一并传入生成服务
- `SpringAiGenerationService`：
  - system prompt 注入 Skill 正文（目标/步骤/禁止行为）与工具范围
  - 校验失败不阻断，异常包装为中文错误
- `MockAiGenerationService`：卡片标题附带已加载技能与工具范围，便于演示

修复：`SkillLoader` Javadoc 中 `ai-skills/*/SKILL.md` 的 `*/` 提前闭合块注释，已改写避免非法字符。

### 20.2 验收结果

真实 `STUDY_PLAN` 任务：

- 任务 SUCCEEDED，actionCount=3
- 第一步"加载技能"消息：`已加载技能[学习计划技能]，工具范围：get_board_context、search_document_chunks、propose_board_actions`，与 `study-plan/SKILL.md` frontmatter 完全一致

### 20.3 状态小结

| Skill 相关能力 | 状态 |
| --- | --- |
| SKILL.md frontmatter 解析 | ✅ |
| 按任务类型匹配 Skill | ✅ |
| 任务步骤展示技能名与工具范围 | ✅ |
| 生成服务注入 Skill 正文与工具限制 | ✅ |
| Skill 存库 / 用户自定义 | ❌ 计划内 MVP 不做 |
| 工具真实执行（只读/提案/受控写入） | ⚠️ 仍为硬编码 apply |

### 20.4 下一步

1. 失败重试与 schema 修复回路
2. 只读工具实现（get_board_context / search_document_chunks 等）
3. 卡片详情 / BlockNote
4. 自动布局与模板

---

## 21. 本轮迭代记录（2026-08-06 四续）：重试回路与只读工具

### 21.1 结构化输出校验 + 重试回路（计划 9.7）

- 新增 `BoardActionValidator`：
  - 空动作、标题为空、端点缺失/自连、重复连线等校验
  - 返回中文校验错误串，空串表示通过
- `BoardActionResult` 增加 `attempts` 字段
- `SpringAiGenerationService` 增加重试循环：
  - 首次校验失败 → 把错误原因反馈给模型要求修复（`previousError` 注入 user prompt）
  - 再失败 → 抛 `AI 动作校验失败`，任务标记 FAILED 不落库
  - system prompt 补充 connect 自连/重复连线约束

验证：`PRODUCT_REQUIREMENT` 任务 SUCCEEDED，resultJson 显示 `attempts:1`。

### 21.2 只读工具（计划 9.2）

- 新增 `BoardReadTools`（`@Tool` 方法）：
  - `get_board_context`：画布标题 + 卡片摘要
  - `get_card_content`：指定卡片完整正文
  - `list_source_files`：画布资料文件名与解析状态
- `SpringAiGenerationService` 通过 `MethodToolCallbackProvider` 注册工具，启动日志打印已注册工具
- `AiToolCall` 实体补构造器与 `succeed/fail`，工具执行结果写入 `ai_tool_calls` 审计（成功/失败 + 耗时）
- `toolContext` 注入 `taskId`，工具调用按任务可追溯

### 21.3 已知限制（如实记录）

- 工具已注册、toolContext 已注入、审计落库就位，且任务主链路（结构化输出）全部 SUCCEEDED
- 但当前 `.call().entity(BoardActionPlan.class)` 使用 `response_format: json_schema`，OpenAI 兼容网关在该模式下**禁用工具调用**，故模型未实际触发 `@Tool` 方法，`ai_tool_calls` 暂无记录
- 后续若要让工具真正执行，需改为两段式：先普通对话（允许工具）收集上下文 → 再结构化输出；或在 JSON schema 模式外单独启用工具

### 21.4 下一步

1. 两段式调用：工具收集上下文 → 结构化输出（让工具真正执行）
2. 卡片详情 / BlockNote
3. 自动布局与模板
4. 只读工具 `search_document_chunks`（需要先做 chunk + embedding）

---

## 22. 本轮迭代记录（2026-08-06 五续）：两段式调用打通工具闭环

### 22.1 改造内容

- `SpringAiGenerationService.generateBoardActions` 改为两段式：
  1. `collectContext`：普通对话（允许工具调用），Spring AI 自动执行 `get_board_context`/`get_card_content`/`list_source_files` 并回传结果，返回概括文本
  2. 结构化输出：`collectContext` 结果注入 user prompt，再走 `entity(BoardActionPlan)` 与重试回路
- `userPrompt` 增加 `toolContext` 参数，把工具收集到的上下文附给规划模型
- 工具阶段失败仅告警降级，不阻塞主任务

### 22.2 验收结果（真实 spring-ai）

`CANVAS_STRUCTURE` 任务：

- 第一阶段真实调用了 2 个工具并落库审计：

| tool_name | status | duration_ms | input |
| --- | --- | --- | --- |
| get_board_context | SUCCESS | 180 | boardId |
| list_source_files | SUCCESS | 86 | boardId |

- 第二阶段结构化输出成功，actionCount=2，任务 SUCCEEDED

结论：**只读工具真实执行 + ai_tool_calls 审计 + 上下文回传规划模型** 的闭环彻底打通，解决了上一轮"json_schema 模式下工具被禁用"的限制。

### 22.3 下一步

1. `search_document_chunks`（需先做 chunk + embedding + pgvector 写入）
2. 卡片详情 / BlockNote
3. 自动布局与模板
4. 两段式上下文缓存与超时控制

---

## 23. 本轮迭代记录（2026-08-06 六续）：自动布局与模板

### 23.1 自动布局

- 新增 `frontend/src/lib/whiteboard/layout.ts`：
  - `autoLayout(spec)`：依据连线把节点切分成连通分量与孤立节点
  - 分量内 BFS 分层（根节点入度为 0 在最左列），层间横向推进、层内纵向错开
  - 孤立节点按 3 列网格排布；分量之间按列拼接，避免重叠
  - 纯函数，仅调整坐标不改内容
- 新增 `autoLayoutTldraw(editor)`（whiteboard-editor 内）：
  - 读取 tldraw 当前画布元素，依据箭头 binding 构建连接图
  - 对 note/geo/text 等非箭头元素分层重排，`updateShapes` 应用
- 工具栏新增"整理"按钮

验证：`autoLayout` 纯函数 tsx 单测通过（根在 (120,120)、子节点同列错开、孤立节点独立列），前端类型检查通过。

### 23.2 内置模板

- 新增 `frontend/src/lib/whiteboard/templates.ts`，4 个模板：
  - `mindmap` 思维导图：中心主题 + 4 分支
  - `study-plan` 学习计划：阶段 → 步骤
  - `prd` 产品需求：目标/用户/范围/里程碑
  - `flowchart` 流程图：开始/处理/判断/结束
- 每个模板 `build(rootTitle)` 返回 `WhiteboardSpec`（nodes + edges）
- 工具栏"模板"按钮弹出模板选择
- `board/[boardId]/page.tsx` 的 `applyTemplate`：模板 spec → 逐卡片 `createCard` → 逐连线 `createEdge` → `loadAll` 刷新，卡片经 mergeMissingCards 进入 tldraw

验证：通过 API 创建根卡+分支卡+连线均成功，模板落库链路（createCard/createEdge）可用。

### 23.3 下一步

1. `search_document_chunks`（chunk + embedding + pgvector）
2. 卡片详情 / BlockNote
3. 模板参数化（分支数/节点数可配置）
4. 自动整理后相机适配（zoom to fit）

---

## 24. 本轮迭代记录（2026-08-06 七续）：类 ProcessOn 白板基础能力

### 24.1 多图形 / 多线 / 属性面板

tldraw 原生已支持 13 种 geo 图形、9 种箭头、12 色、4 种线型，之前被 `hideUi` 隐藏且按钮太少。本轮补齐暴露：

- 工具栏"图形"改为下拉菜单：矩形/椭圆/三角/菱形/五边/六边/八边/星形/云朵/心形/梯形/胶囊，点击即创建对应 geo 图形
- 新增 `ElementInspector`（`frontend/src/components/whiteboard/element-inspector.tsx`）属性面板：
  - 选中元素后底部弹出
  - 切换几何图形类型（geo）
  - 切换线型（实线/虚线/点线/手绘）
  - 切换箭头起终点样式（9 种）
  - 切换颜色（12 色色板）
  - 调整宽高尺寸（数字输入）
  - 旋转 45° / -45°
  - 通过 `editor.store.listen` + `getSelectedShapeIds` 实时反映选中态，`updateShapes` 应用
- `addShape` 支持传入任意 geo 类型

### 24.2 AI 删除动作

- `BoardAction` 增加 `DeleteCard`（cardId）
- `AiTaskService.applyActions` 增加 `applyDeleteCard`：校验画布归属后，删除关联连线（`findByFromCardIdOrToCardId` + `deleteAll`）再删除卡片
- 修复：`@Modifying` JPQL 批量删除在事务边界报 `Executing an update/delete query`，改为实体级删除避免语义冲突

### 24.3 验证结果

- 前端类型检查通过；后端编译通过
- AI 删除任务：`删除标题为 模板验证根 和 分支一 的卡片`
  - 模型识别目标返回 2 个 `delete_card` 动作，任务 SUCCEEDED
  - 卡片从 15 张减到 13 张，目标卡片已删除

### 24.4 可演示能力新增

1. 选择元素后调整图形/线型/箭头/颜色/尺寸/旋转
2. AI 通过自然语言删除指定卡片（连带连线）

### 24.5 下一步

1. `search_document_chunks`（chunk + embedding + pgvector）
2. 卡片详情 / BlockNote
3. AI 生成思维导图/流程图（模型直接输出结构，不只删）
4. 自动整理后相机适配

---

## 17. 当前推荐本地配置

```env
AI_PROVIDER=spring-ai
GPT_API_KEY=          # 建议放系统环境变量，不写进仓库
GPT_BASE_URL=https://apinebula.ai
AI_MODEL_PLANNER=gpt-5.4
AI_MODEL_EMBEDDING=text-embedding-3-large
```

启动：

```powershell
# 基础设施
docker compose -f infra/docker-compose.yml up -d

# 后端 / 前端
./run-backend.ps1
./run-frontend.ps1
```

验收：

1. http://localhost:8080/actuator/health 返回 `UP`  
2. http://localhost:3000/app 能列出画布  
3. 画布内发起“总结资料”，任务成功并出现 AI 卡片  

---

## 25. 本轮迭代记录（2026-08-06 八续）：卡片自由缩放 + Markdown 渲染 + AI 生成思维导图/流程图

### 25.1 自定义卡片 shape（可自由缩放 + Markdown 渲染）

用户反馈：卡片不能自由调大小、AI 生成的 markdown（##、**、列表）原样显示难看。根因是普通卡片用 tldraw 原生 `note` shape（`resizeMode:'none'` 不可缩放、宽度恒 200px、`toRichText` 纯分行不解析 markdown）。

实现：

- 新增 `frontend/src/lib/whiteboard/card-shape-util.tsx`：自定义 `CardShape`（`TLBaseShape<'card', { w, h, title, markdown, cardType }>`）+ `CardShapeUtil extends BaseBoxShapeUtil`
  - 自由缩放：`TLBaseBoxShape` 自带 w/h，`canResize` 默认 true，`BaseBoxShapeUtil` 自带 `onResize`/`getGeometry`
  - Markdown 渲染：`component` 返回 `<HTMLContainer>` + `<ReactMarkdown remarkPlugins={[remarkGfm]}>`
- 安装依赖：`react-markdown@10`、`remark-gfm@4`（原 3.0.1 与 react-markdown 10 类型冲突，升级解决）
- `globals.css` 新增 `.card-shape` 样式（白底圆角阴影、标题栏、正文滚动、markdown 标题/列表/粗体/代码/表格/引用）
- `whiteboard-editor.tsx`：
  - `createTLStore` 与 `<Tldraw>` 两处 `shapeUtils: [...defaultShapeUtils, CardShapeUtil]` 注册
  - `addNote`（用户加卡）、`createCardShape`（SOURCE/其他类型）都改为创建 `type:"card"`，props 带 `w/h/title/markdown/cardType`，meta 带 `cardId`
  - 新增 `migrateLegacyCardShapes`：加载旧 snapshot 时把 id 匹配后端卡片的 note/geo 迁移为 card shape（`extractRichTextText` 提取文本，保留坐标）
  - IMAGE 卡仍用 image shape 不变

验证：前端类型检查通过；旧卡刷新后自动变成 markdown 渲染、可自由缩放。

### 25.2 AI 生成思维导图 / 流程图

- `AiTaskType` 新增 `GENERATE_MINDMAP`、`GENERATE_FLOWCHART`
- `SpringAiGenerationService` 的 system prompt 增加 `structurePrompt`：
  - 思维导图：中心主题卡 + 4-6 分支卡，每个分支 connect 到中心，卡片坐标左→右排布
  - 流程图：开始节点 + 3-5 处理/判断节点 + 结束节点，connect 按流程顺序串联
- `BoardAction.CreateCard` 增加可选 `id`（供 connect 引用本次新卡）
- `AiTaskService.applyActions` 改为两遍：先建所有卡并记录 临时id→真实UUID 映射，再处理 connect（`resolveCardId` 先查映射再按 UUID 解析）
- 前端 `types.ts` AiTaskType、`task-panel.tsx` taskOptions（新增"思维导图"Share2 图标、"流程图"Workflow 图标）

验证（真实 spring-ai）：

- `GENERATE_MINDMAP` 任务"以 个人知识管理 为主题"：模型输出 11 个动作，SUCCEEDED
- 生成中心主题"个人知识管理"+ 5 分支卡（目标与价值/收集与捕捉/整理与分类/复盘与沉淀/工具与流程）
- 5 条连线从中心指向各分支（tempId→UUID 映射生效）

### 25.3 下一步

1. `search_document_chunks`（chunk + embedding + pgvector）
2. 卡片详情编辑（双击编辑 markdown 正文）
3. 流程图/思维导图坐标优化（调用 autoLayout 自动整理）
4. 模板参数化

---

## 26. 认证与权限后续计划（2026-08-07 记录，尚未实现）

参考：`C:\Users\27812\Desktop\interview`（AI Interview Platform）的 JWT 认证实现。
要求：**不使用 RBAC 角色权限模式**（单人应用，owner 即全部权限）。

### 参考实现要点（interview 项目）

- `SecurityConfig`：无状态（STATELESS）、禁用 csrf/formLogin/httpBasic、`/api/auth/**` permitAll、其余 authenticated
- `JwtAuthenticationFilter`（OncePerRequestFilter）：解析 `Authorization: Bearer <token>`，JJWT `parseToken` 校验，把 `userId` + claims 写入 `SecurityContextHolder`
- `JwtUtils`（JJWT 0.12）：HS256，`generateToken(claims, expMs)` / `parseToken` / `validateToken`；accessToken 短过期、refreshToken 长过期
- 登录：`authenticationManager.authenticate` → 生成 accessToken + refreshToken，refreshToken 存 Redis + HttpOnly Cookie
- 注册：`/api/auth/register`，BCrypt 加密密码
- 刷新：`/api/auth/refresh`，读 Cookie → 校验 Redis → 发新 accessToken（带 Redis 分布式锁防并发刷新）

### Doc Canvas 适配方案（不使用 RBAC）

1. 保留 `users` 表（已有 `email/display_name`），增加 `password_hash` 列（Flyway V3 迁移）
2. 新增依赖：`jjwt-api/jjwt-impl/jjwt-jackson`（版本对齐 Spring Boot 3.5 兼容的 0.12.x）
3. 新增：`JwtUtils`、`JwtAuthenticationFilter`、`AuthController`（`/api/auth/register|login|refresh`）、`AuthService`、BCrypt `PasswordEncoder`
4. `SecurityConfig` 改为：`/api/auth/**` permitAll、静态/actuator 放行、其余 API 需认证；401/403 统一中文 JSON
5. `CurrentUserService` 升级：优先从 `SecurityContextHolder` 取认证用户（JWT 中的 userId），缺失时回退演示用户（本地开发）
6. 前端：登录/注册页 + token 存储 + 请求带 `Authorization` 头；登录后可请求头仍兼容 `X-User-Email`（过渡期）
7. **owner 权限**保持现有按 `owner_id` 过滤的服务层逻辑即可，无需角色表

### 不做的

- 不加 `sys_role`/`sys_permission`/RBAC 表（interview 有，本项目不需要）
- 不做 OAuth2 第三方登录（interview 有 oauth2Login，本项目 MVP 不需要）
- 不做多用户组织/团队空间

### 状态

- 待实现。当前仍是演示用户头（`X-User-Email` / `demo@example.com`），可随时从"启动并验证"切换到真实认证。

---

## 27. 本轮迭代记录（2026-08-07）：RAG 链路（chunk + embedding + pgvector + 检索工具）

### 27.1 背景

- 参考 `C:\develop\OpenProject\interview-guide` 的 RAG 实现（Spring AI 2.0 pgvector），但 Doc Canvas 是 Spring AI 1.0.0，本地 m2 无 embedding starter / pgvector store。
- 采用**零新增依赖**的原生方案：RestClient 调 embedding + 原生 SQL 写/查 pgvector。

### 27.2 embedding 提供方（重要）

- 原配置 `text-embedding-3-large`（OpenAI 网关 apinebula.ai）**不可用**：CODEX 分组无 embedding 渠道，`model_not_found`。
- 改用**阿里百炼** `text-embedding-v3`（`BAILIAN_API_TOKEN` 系统环境变量，35 字符）：
  - base-url：`https://dashscope.aliyuncs.com/compatible-mode/v1`
  - 输出 **1024 维** → `document_chunks.embedding` 从 `vector(1536)` 改为 `vector(1024)`（Flyway **V3** 迁移）
- 配置：`app.embedding.api-key/base-url/model`（application.yml），`.env` 加 `BAILIAN_API_TOKEN`，run-backend.ps1 提升该变量。

### 27.3 实现

- `EmbeddingClient`（`service/ai/EmbeddingClient.java`）：RestClient 调 `/embeddings`，批量、`dimensions=1024`，返回 `[a,b,c]` pgvector 文本；失败降级返回空（不阻断主流程）。
- `DocumentChunkRepository`（新建）：
  - `insertWithEmbedding`：原生 SQL `cast(:embedding as vector)` 插入（JPA String 绑定 vector 列会报 "column is of type vector but expression is of type character varying"）
  - `searchIdsByVector`：原生 SQL `embedding <=> cast(:embedding as vector)` 余弦相似度，取 topK id（避免 vector 列映射实体问题）
  - `deleteBySourceFileId`：重传时清旧 chunk
- `DocumentChunkService`：按固定长度（800 字符、重叠 100）切 chunk → 批量 embedding → 原生插入；`search(boardId, ownerId, query, topK)` 检索。
- `DocumentChunk` 实体补构造器/setter，`embedding` 列定义改 1024。
- `FileService.upload`：解析成功后 afterCommit 异步向量化（复用 aiTaskExecutor，避免网络调用阻塞事务）。
- `BoardReadTools` 新增 `search_document_chunks` 工具（boardId、query、topK），owner 校验 + `ai_tool_calls` 审计。
- `SpringAiGenerationService` 的收集阶段与 system prompt 提示模型可调用 `search_document_chunks`。

### 27.4 验证结果

- 上传 `rag-test.md` → parseStatus READY → 异步向量化 `chunks=1`，embedding 维度 1024，落库成功。
- `DOCUMENT_SUMMARY` 任务"检索 RustFS 相关资料片段再总结"：模型在收集阶段调用 `search_document_chunks` 2 次（SUCCESS），任务 SUCCEEDED。

### 27.5 已知限制

- embedding 依赖阿里百炼（`BAILIAN_API_TOKEN`），若未配置则向量化跳过（资料仍可解析进 text_excerpt，只是不可向量检索）。
- `search_document_chunks` 检索的是上传资料；用户仅用画布卡片时无 chunk 可检索。
- 后续可加：query rewrite、多候选检索、短 query 命中确认（参考 interview-guide）。

### 27.6 下一步

1. 卡片双击编辑 markdown 正文（写回 `cards`）
2. 结构图生成后 autoLayout 兜底整理
3. 认证接入（见 §26 计划）
4. 模板参数化

---

## 28. 本轮迭代记录（2026-08-07）：卡片双击编辑 markdown

### 28.1 实现

- `CardShapeUtil`（`card-shape-util.tsx`）：
  - `canEdit() => true`，tldraw 双击自动进入编辑态（`setEditingShape`）
  - `CardComponent` 用 `useEditor().getEditingShapeId() === shape.id` 判断编辑态
  - 编辑态渲染原生 `<textarea>`：自动聚焦全选、Esc 取消、失焦提交
  - 提交时 `editor.updateShape` 更新 shape 的 markdown + `api.updateCard` PATCH 后端
- 后端 `CardService.update` 已有 `PATCH /api/cards/{cardId}`（title/contentMarkdown/cardType），直接复用
- `api.ts` 新增 `updateCard(cardId, { title, contentMarkdown, cardType })`
- `globals.css` 新增 `.card-shape-editing` / `.card-shape-textarea` / `.card-shape-edit-hint`（编辑态边框高亮 + 提示条）

### 28.2 验证

- 前端类型检查通过；`/app` 200
- 实测 `PATCH /api/cards/{cardId}`：卡片 title/contentMarkdown 更新成功并落库（updatedAt 刷新）

### 28.3 备注

- 编辑内容同时写回 shape（画布即时可见）与后端 cards 表（持久化）
- 保存失败静默（不阻断编辑态），后续可加 toast 提示

---

## 29. 本轮迭代记录（2026-08-07）：JWT 认证接入

### 29.1 实现（参考 interview 项目，不用 RBAC）

后端：

- `pom.xml` 加 `jjwt-api/impl/jackson 0.12.6`（复制到项目 `.m2/repository` 离线可用）
- Flyway **V4**：`users` 表加 `password_hash` 列
- `UserAccount` 加 `passwordHash` 字段
- `common/JwtUtils`：HS256 生成/解析 token，accessToken 30 分钟、refreshToken 7 天，claims 带 userId/email
- `common/JwtAuthenticationFilter`：解析 `Authorization: Bearer`，principal 为 userId(UUID) 写入 SecurityContext
- `config/SecurityConfig`：STATELESS、禁 csrf/formLogin/httpBasic、`/api/auth/**` + health + ws + OPTIONS 放行、其余 authenticated；401/403 中文 JSON；`PasswordEncoder` = BCrypt
- `api/dto/AuthDtos`：RegisterRequest（email/displayName/password 校验）、LoginRequest、AuthResponse(token/refreshToken/user)
- `service/AuthService`：register（BCrypt 加密、邮箱去重）、login（校验）、refresh
- `api/AuthController`：`POST /api/auth/register|login|refresh`
- `CurrentUserService`：**优先**从 SecurityContext 取 JWT 认证用户（查库），否则回退请求头/演示用户（本地开发兼容）

前端：

- `lib/api.ts`：`authToken`（localStorage 存 dc_token）+ `authHeaders()`（有 token 用 Bearer，无则演示头）+ 401 自动清 token 跳登录 + `api.register/login`
- `app/login/page.tsx`：登录/注册切换页（邮箱/昵称/密码）
- `app/app/page.tsx`：无 token 跳登录 + 退出登录按钮
- `components/ui.tsx` 加 `Label`

### 29.2 验证结果

- 无 token 访问 `/api/boards` → **401**
- 注册 alice@test.com → 返回 token(209) + user
- 登录 → token
- 带 `Authorization: Bearer` 访问 → **200**
- 错误密码 → `邮箱或密码错误`
- 前端 `/login`、`/app` 均 200 编译正常

### 29.3 说明

- 有 token 时按 JWT 用户隔离数据（owner_id 过滤已有）
- 无 token 时回退演示用户（`X-User-Email`），保证旧客户端/本地开发仍可用
- refreshToken 当前仅返回未实际校验（简化）；多端登录、token 撤销、Redis 会话后续可加
- 认证不影响 AI/文件/画布所有现有功能

---

## 30. 本轮迭代记录（2026-08-07）：基础功能补齐

### 30.1 删除画布 / 卡片

- 后端：`DELETE /api/boards/{boardId}`、`DELETE /api/cards/{cardId}`
  - `BoardService.delete`（owner 校验 + 级联删 cards/edges/snapshot）
  - `CardService.delete`（owner 校验 + 删关联连线）
- 前端：工作台画布卡片 hover 删除按钮（confirm）；`api.deleteBoard/deleteCard`

### 30.2 重命名 + 卡片标题编辑

- 画布标题点击重命名（`PATCH /api/boards/{id}` + 前端内联输入）
- 卡片双击编辑态：标题输入框 + 正文 textarea，退出时一起保存（shape + 后端 cards）

### 30.3 导出 PNG / Markdown

- 工具栏"导出"下拉：PNG（`editor.toImage` 下载 blob）、Markdown（卡片 title+content 拼 .md）

### 30.4 PDF / DOCX 解析（参考 interview-guide）

- pom 加 `tika-core` + `tika-parsers-standard-package 2.9.2`（联网从 alimaven 下载，离线拷贝到项目 m2）
- `DocumentParseService`：AutoDetectParser + BodyContentHandler，PDF 关图片提取/按位置排序，不提取嵌入资源
- `FileService.parseTextContent` 扩展：TXT/MD 直接读 UTF-8，PDF/DOCX/DOC/XLSX/PPTX 用 Tika，其余 PENDING
- 解析成功 → text_excerpt + READY → 复用已有向量化链路（chunk + embedding + pgvector + search_document_chunks）

### 30.5 验证

- 画布重命名/删除 200，卡片删除 200（cards left 0）
- PDF 上传 → parse READY，文本提取成功（`Tika PDF text extraction test 2026`）
- 认证后 API 需 token（`X-User-Email` 演示头被 401 拦截，前端走登录页）

### 30.6 说明

- SecurityConfig 现要求 API 认证；前端无 token 时 `/app` 跳登录页
- 旧的 `X-User-Email` 演示头仅在 `CurrentUserService` 层保留回退（但 Security 已拦截，实际需走登录）

---

## 31. 本轮迭代记录（2026-08-07）：5 个使用问题的修复 + 结构图布局升级

### 31.1 问题与修复

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 1. 删除后刷新复活 | 删除只删 shape 不删后端 cards，刷新 mergeMissingCards 用 cards 重建 | deleteSelected 同步 api.deleteCard + 更新 knownCardIds + IMAGE 补 meta.cardId |
| 2. 卡片/线相交 | autoLayoutTldraw binding 遍历 from/to 计算相同（bug） | 修 binding 方向 + 每层节点横向并排、层间纵向堆叠 |
| 3. 无法拖动/位置固定 | hideUi 隐藏手工具、无平移按钮；nextPlacement 用固定页面坐标 | 加"平移"工具按钮 + 提示；add 元素用 getViewportPageBounds().center |
| 4. 工具命中率低 | 工具第一阶段可选调用、模型不调；工具用 demoUser 导致认证画布 owner 校验失败 | collectContext 强制必调 3 工具；toolContext 传 ownerId，工具用真实 owner |
| 5. 结构图简单 | 模型自行排坐标、节点少 | 方案 B：模型只出结构（id 节点+连线），后端 GraphLayoutService 布局 |

### 31.2 结构图布局（方案 B）

- 新增 `GraphLayoutService`：
  - `layoutMindmap`：中心辐射（根居中、分支沿圆分布）
  - `layoutFlowchart`：BFS 分层（入度 0 为根，每层纵向排列、层间横向推进）
- `AiTaskService.maybeLayoutGraph`：对 MINDMAP/FLOWCHART 任务，收集 create_card（id 作节点）+ connect（边），布局后重建 create_card 坐标再落库
- `structurePrompt` 强化：节点必须带 id、不输出坐标、思维导图 5-8 分支、流程图 4-7 节点（判断用 QUESTION）
- systemPrompt 对结构图任务放宽"1-3 个动作"限制

### 31.3 验证

- 问题 4：模型强制调用 get_board_context + list_source_files + search_document_chunks 3 个工具全 SUCCESS
- 思维导图"人工智能学习路线"：中心(290,230) + 6 分支环绕，标准中心辐射
- 流程图"用户注册"：开始→填写→校验→账号判断→创建→验证判断→成功→结束，y 一致横向推进，判断节点为 QUESTION

### 31.4 说明

- 工具命中率从"常为 0"提升到"必调 3 个"
- 结构图坐标由后端算法保证不重叠、箭头贴边（tldraw 箭头自动吸附节点边缘）

---

## 32. 本轮迭代记录（2026-08-07）：AI 内容深度改造（策略问题）

### 32.1 结论：策略问题，非模型问题

对照实验（同一模型 gpt-5.4）：

| 维度 | 弱 prompt（原系统） | 强 prompt（约束版） |
| --- | --- | --- |
| 输出长度 | 718 字符 | 2504 字符 |
| completion_tokens | 661 | 1809 |
| 结构 | 一段摘要 | 多章节、要点含观点+论据+实例 |

证实模型能力足够，是系统 prompt 引导过浅。

### 32.2 根因

1. prompt 无深度约束：只要求"生成一张有用的卡片"
2. 无 max_tokens：内容长时可能被截断
3. 单次调用，未用满模型 reasoning（实测 reasoning_tokens 仅 183）

### 32.3 修复

- `ChatClient.defaultOptions` 设 `maxTokens(2000)`（ChatOptions.builder）
- `userPrompt` 新增 `depthRequirement(taskType)`：
  - 通用：≥3 章节、每要点含「核心观点+论据+例子/数据」、引用资料原文、≥400 字
  - 差异化：PRODUCT_REQUIREMENT 覆盖目标/用户/功能/风险/里程碑；STUDY_PLAN 覆盖阶段/练习/验收；CANVAS_STRUCTURE 覆盖结构总结/分组/建议
- 结构图任务不套用深度模板（坐标/结构由布局器处理）

### 32.4 验证

- "总结 RustFS 资料"任务：正文 1618 字符（改造前约 700），多章节结构，要点含观点+论据+资料原文引用

---

## 33. 本轮迭代记录（2026-08-07）：UX 优化 + 视觉美化

### 33.1 平移画布（免来回切换）

- `onMount` 加 `editor.focus()`：画布获得焦点，Delete/空格快捷键首击即生效
- `editor.setCameraOptions({ wheelBehavior: "pan" })`：滚轮直接平移画布（默认是缩放）
- 平移方式汇总：滚轮拖动、中键拖动、空格+拖动、"平移"手工具按钮（已加）
- 提示文案更新

### 33.2 Delete 删除

- `editor.focus()` 修复焦点问题，tldraw 内置 Delete/Backspace 删除快捷键现在首击生效（之前点工具栏按钮后焦点在按钮上，Delete 失效）
- 删除同步后端（沿用问题 1 的修复）

### 33.3 视觉美化：卡片按类型配色

- `card-shape-util.tsx`：`cardTypeClass(cardType)` 映射配色类
- `globals.css`：每种类型不同左边条 + 标题底色
  - SUMMARY 绿、IDEA 紫、SOURCE 蓝、QUESTION 橙、NOTE 灰、IMAGE 橙红
- 视觉层次更清晰，一眼区分卡片类型

---

## 34. 本轮迭代记录（2026-08-07）：Ctrl+Z 回滚 + Delete 修复

### 34.1 Delete 修复（上一轮补充确认）

- 根因：`hideUi` 移除了 tldraw 内置 Delete/Backspace 快捷键（action 绑定在 ui 层）
- 修复：全局 keydown 监听，选中元素按 Delete/Backspace 调 deleteSelected（删 shape + 同步后端 + 存墓碑），输入框内不触发

### 34.2 Ctrl+Z / Ctrl+Shift+Z 回滚

- 全局 keydown 绑定：Ctrl+Z → `editor.undo()`，Ctrl+Shift+Z → `editor.redo()`
- tldraw 原生历史栈覆盖：移动、缩放、增删 shape、连线等
- 难点：删除 shape 时同步删了后端 cards，undo 恢复 shape 但后端记录没了
- 方案：删除时把卡片数据存 `deletedCardsRef`（墓碑）；store.listen 检测 undo 后 shape 重新出现 → `api.createCard` 重建后端 + 更新 knownCardIds
- 效果：Ctrl+Z 删除卡片后，画布 shape 恢复 + 后端卡片重建，刷新也不丢

---

## 35. 本轮迭代记录（2026-08-09）：文件悬浮窗 + 文件删除 + AI 对话式工作台

### 35.1 文件面板改悬浮窗

- `SourcePanel` 从固定左侧栏改为悬浮窗
- 工具栏新增"资料"按钮（FolderOpen 图标），点击切换显隐
- 点击画布区域自动隐藏（section onClick），工具栏按钮 stopPropagation 防误关

### 35.2 文件上传后可删除

- 后端：`ObjectStorageService` 加 `delete(key)`，`RustFsObjectStorageService` 用 S3 deleteObject
- `FileService.delete`：删 RustFS 对象 + DB 记录（向量 chunk 外键级联删）
- `FileController` 加 `DELETE /api/boards/{boardId}/files/{fileId}`
- 前端：api.deleteFile + SourcePanel 每项删除按钮（confirm）
- 验证：上传→删除 200→列表空

### 35.3 AI 对话式工作台

- `TaskPanel` 从"选类型 + 输入"改为**完全对话式**：
  - 消息列表（用户右对齐气泡 / AI 左对齐 + 任务卡片）
  - 底部输入框，回车直接发送，Shift+Enter 换行
  - `inferTaskType` 按关键词自动推断类型（思维导图/流程/总结/学习计划/产品/图片/整理）
  - 任务卡片显示：状态、进度条、步骤序号列表、错误信息
  - 运行时显示"第 N / 总步数 步"（从 task.steps 计算）
  - 中断按钮内联在任务卡片

### 35.4 备注

- 修复：此前重启后端静默失败（端口被旧进程占用），导致"画布不存在"假象；已确认新进程正常启动

---

## 最终 AI 模型切换结论（2026-08-10）

### 根因

在 apinebula.ai 网关 + gpt-5.4 上，**Spring AI 1.0.0 的 `OpenAiChatModel`/`OpenAiApi` 发出的 HTTP 请求与网关存在兼容性问题**，表现为所有 chat 调用（含工具/不含工具/json_object/无响应格式）均返回 `end-of-input` 截断 JSON。无论换 JDK HttpClient 还是 Apache HttpClient5，无一例外。curl 直连同一网关却全部成功——说明问题在 Spring AI 的请求封装层（UA/header 顺序/URL 处理等细节）。

### 解决

切换到**阿里百炼 DashScope 兼容模式**：`GPT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode`，模型 `qwen-plus`，密钥 `BAILIAN_API_TOKEN`（系统环境变量）。所有任务类型恢复正常。

### 验证

- DOCUMENT_SUMMARY：3 张深度结构卡（1221 字符，引用原文）
- GENERATE_MINDMAP：8 卡 + 7 连线
- GENERATE_FLOWCHART：6 卡 + 5 连线
- CANVAS_STRUCTURE：整理画布结构
- 工具调用（collectContext）因百炼兼容性暂时用内联上下文替代（inlineContext），功能等价

### 当前配置

- `.env`: `GPT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode`，`AI_MODEL_PLANNER=qwen-plus`
- `application.yml`: `api-key=${BAILIAN_API_TOKEN:${GPT_API_KEY:...}}`，`base-url=${GPT_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}`
- `run-backend.ps1`: 把 `BAILIAN_API_TOKEN` 从 User/Machine 提升到 Process

### 已知限制

- 百炼在 Spring AI tool calling 上有兼容性问题（collectContext 不可用），暂时用 inlineContext 替代
- embedding 仍需验证百炼 text-embedding-v3 是否与 document_chunks 维度（1536）匹配


---

## 35. 本轮迭代记录：AI 对话式交互

### 35.1 新增纯聊天接口

- 后端 `ChatService`：纯文本对话，不生成 JSON 动作、不落库
- 后端 `ChatController`：`POST /api/boards/{boardId}/chat`，接收 `{ prompt }`，返回 `{ reply }`，注入画布标题+卡片数让 AI 感知上下文
- 前端 `api.chat(boardId, prompt)`

### 35.2 前端智能路由

- `TASK_PATTERNS` 关键词表：明确任务意图（生成思维导图/流程图/总结/学习计划/产品需求/整理）→ 走任务流程
- 无明确匹配 → 走聊天（纯文本回复，不落卡）
- 闲聊回复展示为左对齐 Bot 对话气泡

### 35.3 TaskCard 流水线优化

- 横向流水线步骤条：✓完成 / …执行中(脉冲) / 序号待执行
- 当前步骤实时 message 展示（animate-pulse）
- 进度条 transition-all

### 35.4 验证

- 闲聊"你是谁" → 文本回复（271 字，介绍能力），画布无新卡
- "帮我生成思维导图" → 文本引导（148 字），不落卡
- 明确"生成人工智能学习路线思维导图" → 走任务（matchTaskType 命中）

### 35.5 已知限制

- 聊天无历史上下文（单轮），后续可传 history
- 聊天回复一次性返回（非流式），后续可 SSE
- 刷新后对话历史清空（不做持久化）


### 35.6 修复：聊天读不到文档 + 任务重复 + 会话丢失

- **聊天读不到文档**：ChatService 原只传画布标题+卡片数。改为 ChatController 用 `fileService.parsedSources(boardId, ownerId)` 注入已解析文档内容到 system prompt。
- **任务出现两次**：原 useEffect 用 `messages.some` 检查（异步 state 可能重复）。改为 `shownTaskIdsRef`（ref 同步去重）。
- **会话刷新丢失**：messages 纯组件 state。改为 localStorage 按画布隔离持久化（`dc_chat_{boardId}`），只存 user/assistant 文本（任务卡不持久化）。
- **快捷按钮被删**：恢复任务类型快捷按钮（总结/思维导图/流程图等），点击预填 prompt。

验证：上传 doc-test.txt 后问"文档核心功能"，AI 准确列出五项。


### 35.7 修复：任务状态不更新 + 资料面板无法隐藏

- **任务状态不更新**：TaskCard 展示的是任务 push 进 messages 时的快照，之后 tasks 更新但 message.task 没同步。改为渲染时 `tasks.find(t => t.id === message.task.id)` 取最新任务对象，进度/状态/步骤实时同步。
- **资料面板无法隐藏**：SourcePanel 弹出后点击面板外空白不关闭（section 的 onClick 被工具栏 stopPropagation 挡住，面板 z-30 盖在画布上）。加一个 inset-0 遮罩层 z-20，点击面板外任意处关闭。


### 35.8 修复：上传图片前端显示失败

- **根因**：接入 JWT 后 `GET /api/boards/{boardId}/files/{fileId}/content` 被 SecurityConfig `anyRequest().authenticated()` 拦截，但 `<img src>` 无法带 `Authorization` 头 → 图片加载 401 → 前端显示失败。
- **修复**：
  1. 后端 `JwtAuthenticationFilter` 支持从 query param `token` 解析认证（图片直链场景）
  2. 前端 `api.fileContentUrl` 在带 token 时拼接 `?token=<jwt>` query 参数
- **验证**：上传 PNG，无 token 访问 content → 401；带 `?token=` 访问 → 200

