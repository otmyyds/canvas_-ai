# 架构说明

## AI 任务边界

模型不能直接写入数据库或对象存储。它只能返回画布动作提案或图片提示词规范。Java 后端负责校验、落库、审计和事件推送。

## WebSocket 范围

WebSocket 只用于任务事件：

- 任务已创建
- 步骤进度
- 画布动作提案/已应用
- 任务完成
- 任务失败
- 任务中断

画布权威状态保存在 PostgreSQL，断线后可通过 REST 恢复。

## 取消机制

取消是协作式的。API 会设置 `ai_tasks.abort_requested`，并写入 Redis 取消标记。Worker 在每个安全步骤边界、以及应用画布写入前检查取消状态。

## MVP AI 提供方

默认 `AI_PROVIDER=mock`，用于在没有模型密钥时跑通编排链路。设置为 `AI_PROVIDER=spring-ai` 后启用 Spring AI 实现。
