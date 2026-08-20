-- 为 AI 任务持久化请求上下文（如选中的卡片/图形节点 id），供异步执行时还原
ALTER TABLE ai_tasks ADD COLUMN context_json text;
