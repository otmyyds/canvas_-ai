# Doc Canvas — 个人知识画布

Doc Canvas 是一个面向个人使用的无限画布应用：在画布上用节点和连线组织知识，支持思维导图、逻辑图、流程图、手绘图形、图片和画布封面，支持本地保存与导出。

项目有两个使用形态：

- **本地轻量版**：Windows/Linux 便携包，SQLite + 本地文件存储，不包含 AI，不需要安装 Java、Node、PostgreSQL 或 Docker；
- **完整 AI 版**：源码自部署，使用 PostgreSQL + Redis + RustFS，提供 AI 生成、资料上传和 RAG 等能力。

> 当前版本：`1.0.0`。AI 功能仍处于早期阶段，适合体验和辅助整理，不建议用于关键生产决策。

## 快速开始

### 方式一：Windows 便携版（普通用户推荐）

适合不想安装数据库和开发环境的用户。

1. 打开发布页下载 `DocCanvas-Lite-Windows.zip`：
   - Gitee Releases：https://gitee.com/buxiangzuoti/canvas_-ai/releases
   - GitHub Releases：https://github.com/otmyyds/canvas_-ai/releases
2. 解压到一个可写目录，例如 `D:\DocCanvas-Lite`，不要放在 `C:\Program Files` 下。
3. 双击 `start.bat`。
4. 浏览器会自动打开 `http://127.0.0.1:3000`。
5. 首次进入创建本地管理员，默认值：
   - 邮箱：`admin@doccanvas.com`
   - 密码：`DocCanvas123`
   - 可以修改后再创建。
6. 创建成功后自动登录。

如果 3000 端口被占用，可以改用其他前端端口：

```powershell
cd D:\DocCanvas-Lite
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -FrontendPort 13000
```

然后访问 `http://127.0.0.1:13000`。

常用文件：

- `start.bat`：启动；
- `stop.bat`：停止；
- `backup.bat`：备份 `data` 目录；
- `restore.bat`：恢复备份；
- `data/`：SQLite、图片、封面、备份和日志。

Windows 首次运行可能出现“未知发布者”提示，这是因为程序未签名；确认来源后选择“仍要运行”。

### 方式二：Linux 便携版

如果发布页提供 `DocCanvas-Lite-Linux.tar.gz`：

```bash
tar -xzf DocCanvas-Lite-Linux.tar.gz
cd DocCanvas-Lite-Linux
./start.sh
```

启动后访问 `http://127.0.0.1:3000`。停止：

```bash
./stop.sh
```

Linux 版同样使用 SQLite 和本地文件存储，不包含 AI。

### 方式三：源码开发/完整 AI 版

前置条件：

- JDK 21；
- Maven；
- Node.js 20+；
- Docker Desktop。

1. 复制环境变量：

```powershell
Copy-Item .env.example .env
```

2. 生成 JWT 密钥并填入 `.env`：

```bash
openssl rand -hex 32
```

```env
JWT_SECRET=上一步生成的64位十六进制字符串
```

3. 如需 AI 功能，配置模型密钥：

```env
BAILIAN_API_TOKEN=
# 或 GPT_API_KEY / OPENAI_API_KEY
AI_MODEL_PLANNER=qwen-plus
EMBEDDING_MODEL=text-embedding-v3
```

4. 一键启动：

```powershell
.\run-all.ps1
```

它会启动 PostgreSQL、Redis、RustFS，并分别启动后端和前端。

也可以手动启动：

```powershell
docker compose -f infra/docker-compose.yml up -d
.\run-backend.ps1
.\run-frontend.ps1
```

访问地址：

- 前端：http://localhost:3000
- 后端健康检查：http://localhost:8080/actuator/health
- RustFS 控制台：http://localhost:9001

开发版默认管理员：

```text
admin@doccanvas.com / DocCanvas123
```

首次登录后建议修改密码。

## 版本对比

| 能力 | 本地轻量版 | 完整 AI 版 |
|---|---|---|
| 无限画布、节点、连线 | 支持 | 支持 |
| 树形图/逻辑图/流程图 | 支持 | 支持 |
| 手绘图形与纳管 | 支持 | 支持 |
| 图片上传 | 支持 | 支持 |
| 画布封面 | 支持 | 支持 |
| PNG / Markdown 导出 | 支持 | 支持 |
| SQLite 本地存储 | 支持 | 可选 |
| PostgreSQL + pgvector | 不包含 | 支持 |
| AI 生成与计划任务 | 不包含 | 实验性支持 |
| 资料上传与 RAG | 不包含 | 支持 |
| 游客/在线托管 | 不包含 | 不属于本仓库默认形态 |
| 注册登录 | 本地管理员 | 支持 |
| 安装要求 | 解压即用 | Docker、JDK、Node |

分享链接目前只有预存实现，没有完整的创建、导入、只读访问和权限闭环，暂不作为可用功能宣传。

## 主要功能

### 画布

- 无限画布，滚轮/中键平移；
- 文本、矩形、椭圆、菱形、云朵等多种图形；
- 线条、箭头、手绘图形；
- 图形属性：类型、线型、箭头、颜色、尺寸、旋转；
- 快照防抖保存，刷新恢复；
- 节点折叠/展开；
- 逻辑图同级排序。

### 布局

- **树形图（径向）**：根节点居中，分支向外辐射；
- **逻辑图**：根节点居中，一级分支左右展开，适合大图；
- **流程图**：按层级自上而下；
- 新节点创建、标题修改、删除后自动重排所属连通分量；
- 「整理」会把画布上的手绘图形和箭头纳管进结构，再整体重排。

### 图片与封面

- 支持 PNG/JPEG 上传，服务端重新编码后保存；
- 图片不再以 data URL 写入快照，减小快照体积；
- 保存画布后自动生成工作台封面；
- 退出画布前会强制保存并更新封面；
- 清空画布会删除旧封面；
- 覆盖封面、删除封面、删除画布都会清理对应本地文件。

### 导出

- PNG；
- Markdown 标题大纲。

### AI（实验性）

完整版提供：

- 资料总结；
- 生成思维导图/流程图；
- 学习计划、产品需求等结构化生成；
- 编辑选中元素；
- 整理画布；
- AI 生图；
- 多步骤计划、实时进度、中断与回退。

当前边界：

- AI 计划执行期间画布只读，避免用户修改被检查点回退覆盖；
- 画布 `shapes + board_edges` 超过默认 `5000` 行时，AI 计划会被拒绝，避免万级节点检查点耗尽内存；
- 检查点 JSON 默认上限 `32MB`；
- AI 计划失败会按计划开始前的检查点回退，超大画布不适合使用整表快照回退；
- AI 质量依赖所配置的模型，仍可能生成不符合预期的结构。

检查点上限可配置：

```yaml
app:
  ai:
    checkpoint:
      max-board-nodes: 5000
      max-bytes: 33554432
```

对应环境变量：

```text
AI_CHECKPOINT_MAX_BOARD_NODES
AI_CHECKPOINT_MAX_BYTES
```

### 资料与 RAG

完整版支持上传 TXT、Markdown、PDF、DOCX 等资料：

1. 上传文件；
2. 后端解析文本；
3. 分块并调用 embedding 模型；
4. 写入 pgvector；
5. AI 任务可按需检索相关片段。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Next.js 15 App Router · React 19 · TypeScript · tldraw 3 · zustand · Tailwind CSS · STOMP/SockJS |
| 后端 | Java 21 · Spring Boot 3.5 · Spring Security · Spring Data JPA · Spring AI 1.0 · WebSocket/STOMP |
| 数据 | PostgreSQL + pgvector、SQLite（轻量版）、Flyway |
| 缓存/异步 | Redis、Redisson |
| 存储 | RustFS（S3 兼容）或本地文件系统 |
| AI | 阿里百炼 DashScope（qwen-plus / text-embedding-v3 / wanx 文生图）、SerpAPI |
| 打包 | 内置 JRE（jlink）+ Next standalone + Node 运行时 |

## 目录结构

```text
backend/                 Spring Boot 后端
  src/main/java/         业务代码、AI、布局、存储
  src/main/resources/    配置、Flyway 迁移、提示词
  src/test/java/         后端测试
frontend/                Next.js 前端
  src/app/               页面与路由
  src/components/        画布、工作台、对话框
  src/lib/               API、状态、布局同步
infra/                   PostgreSQL/Redis/RustFS 的 docker compose
scripts/
  build-lite-windows.ps1 Windows 便携版构建
  build-lite-linux.sh    Linux 便携版构建
  lite/windows/          Windows 启动、停止、备份、迁移脚本
  lite/linux/            Linux 启动、停止、备份、迁移脚本
assets/                  README 截图
```

## 配置说明

### Spring Profile

| profile | 数据库 | 文件存储 | AI | 用途 |
|---|---|---|---|---|
| `lite` | SQLite | `data/storage` | 关闭 | 普通用户便携版 |
| 默认/`dev` | PostgreSQL | RustFS/S3 | 开启 | 开发者 |

便携版启动时会自动使用 lite profile，并自动生成本机 `JWT_SECRET`，用户不需要手动配置。

源码启动时必须在 `.env` 或系统环境变量中提供 `JWT_SECRET`。

### 数据目录

轻量版数据默认在程序目录下的 `data/`：

```text
data/
  doc-canvas.db
  jwt.secret
  storage/
    images/
    covers/
  backups/
  logs/
```

可以通过 `DOC_CANVAS_DATA_DIR` 修改位置。

## 备份、恢复与迁移

### 便携版备份

先运行 `stop.bat` / `./stop.sh`，再执行：

- Windows：`backup.bat`；
- Linux：`./backup.sh`；
- 备份文件在 `data/backups`。

恢复会覆盖当前 `data`，恢复前会自动生成一份 `pre-restore` 备份。

### PostgreSQL -> SQLite 迁移

便携版内置一次性迁移工具：

- Windows：`migrate-pg-to-sqlite.bat`；
- Linux：`./migrate-pg-to-sqlite.sh`。

迁移用户、画布、节点、连线和最新快照。图片文件、封面、AI/RAG/Agent 数据不迁移，迁移后封面字段会置空。

目标 SQLite 已有数据时，需要 `-Force` / `--force`。

## 开发与测试

### 后端测试

```bash
mvn -f backend/pom.xml test
```

部分测试使用 Testcontainers，需要本机 Docker 运行。

### 前端检查

```bash
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run lint
npm --prefix frontend run build
```

### 构建便携版

```powershell
# Windows
.\scripts\build-lite-windows.ps1
```

```bash
# Linux（在 Linux 或 CI 上执行）
bash scripts/build-lite-linux.sh
```

产物：

```text
dist/DocCanvas-Lite-Windows.zip
dist/DocCanvas-Lite-Linux.tar.gz
```

## 已知限制

- AI 仍处于早期阶段，生成质量和任务稳定性还不够，后续会继续改进；
- AI 计划执行期间画布只读；
- 超大画布会拒绝 AI 计划，避免检查点拖垮服务；
- 分享功能未完成，暂不对外承诺；
- PostgreSQL -> SQLite 迁移不包含图片和封面文件；
- 多用户实时协作不在当前范围内；
- 在线托管版和本地版的数据不自动同步，需要导出/导入或迁移工具。

## 许可证

MIT License，见 [LICENSE](LICENSE)。
