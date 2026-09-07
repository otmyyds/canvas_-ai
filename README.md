# Doc Canvas — 单人 AI 文档画布

一个面向单人的 AI 文档画布应用：上传资料，在无限画布上用图形节点组织知识（思维导图/流程图），通过 AI 自动总结、生成结构图、整理与编辑，画布内容可实时保存、分享、导出。

## 能力一览

- 无限画布：文本、多种图形、线条、箭头、图片（tldraw 原生 geo 渲染 AI 节点）
- 属性面板：图形类型 / 线型 / 箭头 / 颜色 / 尺寸 / 旋转
- 快照防抖保存，刷新恢复
- 上传 TXT / Markdown / PDF / DOCX 等 → 自动解析 + 异步向量化（pgvector），支持 RAG 检索
- AI 任务：总结资料、整理画布、生成思维导图/流程图、学习计划、产品需求、编辑选中元素、清空画布、文生图；步骤/进度实时推送，可中断
- AI 助手统一入口：后端意图分类（聊天 or 任务）+ 多步骤任务链 + 纯文本聊天（带 RAG）+ 联网搜索
- 只读分享链接、PNG / Markdown 导出
- JWT 认证（注册/登录/静默续期），数据按 owner 隔离

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | Next.js 15 App Router · React 19 · TypeScript · tldraw 3 · zustand · Tailwind CSS · @stomp/stompjs + sockjs-client |
| 后端 | Java 21 · Spring Boot 3.5 · Spring AI 1.0 · Spring Security · Spring Data JPA · WebSocket/STOMP |
| 数据 | PostgreSQL + pgvector · Flyway · Redis（取消标记） |
| 存储 | RustFS（S3 兼容） |
| AI | 阿里百炼 DashScope（qwen-plus / text-embedding-v3 / wanx 文生图）· SerpAPI 联网搜索 |

## 本地启动

```powershell
# 1. 基础设施（PostgreSQL+pgvector / Redis / RustFS）
docker compose -f infra/docker-compose.yml up -d

# 2. 后端（加载 .env + 系统环境变量，mvn spring-boot:run）
.\run-backend.ps1

# 3. 前端（npm run dev）
.\run-frontend.ps1
```

访问地址：

- 前端：http://localhost:3000/app
- 后端健康检查：http://localhost:8080/actuator/health

## AI 配置

后端优先读取百炼密钥，其次 `GPT_API_KEY` / `OPENAI_API_KEY`：

```env
AI_PROVIDER=spring-ai          # mock | spring-ai（默认 spring-ai）
BAILIAN_API_TOKEN=             # 百炼密钥（优先）
AI_MODEL_PLANNER=qwen-plus     # 百炼 DashScope 兼容模式
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_DIMENSIONS=1024
SERPAPI_API_KEY=               # 联网搜索（可选）
NEXT_PUBLIC_API_BASE=http://localhost:8080
```

- `AI_PROVIDER=mock` 时不调真实模型，只跑通任务进度/中断链路。
- 其余 `POSTGRES_*` / `REDIS_*` / `RUSTFS_*` 见 `application.yml` 默认值。

## 架构要点

- **AI 只提案、后端落库**：模型产出结构化 `BoardAction`（建节点/连线/删节点），后端统一校验、解析临时 id、两遍落库，模型不直接写库。
- **树型任务确定性解析**：思维导图/总结等让模型输出 Markdown 标题大纲，后端按标题层级解析成节点树，绕开脆弱的结构化 JSON。
- **意图分类后端化**：`POST /assist` 统一入口，后端一次 LLM 调用拆解「聊天 or 有序任务计划」，多步骤任务链式续跑。
- **Skill 系统**：每类任务的提示词与工具范围沉淀为 `ai-skills/*/SKILL.md`，加载后按 `allowed_tools` 过滤暴露工具。
- **RAG**：上传 → 分块 → 向量化 → 检索，检索为空不退回全量注入。

更完整的方案见 [project_plan.md](project_plan.md)；项目亮点、难点与水平评估见 [项目亮点与难点.md](项目亮点与难点.md)；架构与排障见 [docs/](docs/)。

## 项目状态

功能层面已是一套可完整演示的单人 MVP（纵切齐全）。**注意**：当前无自动化测试、无 CI，JWT 密钥为源码内固定值，属于「高完成度 MVP、未到企业级」状态，详见 [项目亮点与难点.md](项目亮点与难点.md) 第三节的客观评估。
