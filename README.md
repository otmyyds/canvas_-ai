# Doc Canvas

单人 AI 文档画布项目骨架。当前版本已实现 Java 后端、Next.js 前端、RustFS 文件存储入口、AI 异步任务和 WebSocket 进度展示。

## 技术栈

- Backend: Java 21, Spring Boot 3, Spring WebSocket/STOMP, Spring Data JPA, PostgreSQL, Redis, Spring AI
- Frontend: Next.js App Router, React, TypeScript, Tailwind CSS, Zustand, STOMP client
- Infra: PostgreSQL + pgvector, Redis, RustFS

## AI 配置

如果你已经在系统环境变量里配置了 `GPT_API_KEY`，不需要再在 `.env` 里填写 key。后端会直接读取系统环境变量：

```powershell
GPT_API_KEY=你的系统环境变量
```

请求地址已默认配置为：

```powershell
GPT_BASE_URL=https://apinebula.ai/v1
```

默认 `AI_PROVIDER=mock` 不会真正调用 AI，只用于跑通任务进度和中断流程。要调用真实 AI，改成：

```powershell
AI_PROVIDER=spring-ai
```

可以在系统环境变量里设置，也可以在项目根目录 `.env` 里设置。`run-backend.ps1` 会优先保留系统环境变量，`.env` 只补充当前进程里缺失的值，不会用空值覆盖系统变量。

常用配置：

```powershell
AI_MODEL_PLANNER=gpt-4.1
AI_MODEL_EMBEDDING=text-embedding-3-large
```

如果 `apinebula.ai` 支持的模型名不同，把上面两个模型名改成该平台实际支持的模型名即可。

## 本地启动

先启动基础设施：

```powershell
docker compose -f infra/docker-compose.yml up -d
```

启动后端：

```powershell
.\run-backend.ps1
```

启动前端：

```powershell
.\run-frontend.ps1
```

访问地址：

- Frontend: http://localhost:3000/app
- Backend health: http://localhost:8080/actuator/health

## 常见问题

### No plugin found for prefix 'spring-boot'

不要在根目录直接运行 `mvn spring-boot:run`。真正的 Maven 项目在 `backend/pom.xml`。请使用：

```powershell
.\run-backend.ps1
```

或者：

```powershell
mvn -f backend/pom.xml "-Dmaven.repo.local=.\.m2\repository" spring-boot:run
```

## 当前已完成的纵切

- 创建和打开私有画布
- 创建基础卡片
- 上传文件到 RustFS 的接口
- 创建 AI 任务
- 后端异步执行 AI/mock AI 任务
- AI 任务步骤落库
- WebSocket 推送任务进度
- 用户中断任务
- 任务完成后后端应用受控 `create_card` 动作

## 下一步

- 接入 tldraw 自定义卡片 shape
- 接入 BlockNote 富文本编辑
- 做文件解析、chunk、embedding 和 pgvector 检索
- 将 Spring AI 输出改成严格 JSON Schema/DTO 校验
- 实现图片生成、RustFS asset 记录和图片卡片
