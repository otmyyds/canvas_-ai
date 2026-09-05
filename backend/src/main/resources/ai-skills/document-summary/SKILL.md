---
slug: document-summary
name: 资料摘要技能
version: 1
task_types:
  - DOCUMENT_SUMMARY
allowed_tools:
  - get_board_context
  - search_document_chunks
max_steps: 8
max_model_calls: 3
max_tool_calls: 8
---

# 目标

把选中的资料、摘录整理成图形节点树（标题大纲）：以资料主题为根，核心要点为分支。

# 输出

只输出 # 到 ##### 标题大纲，不要输出动作、JSON、正文或解释。后端会按标题层级确定性生成图形节点与连线。
