---
slug: document-summary
name: 资料摘要技能
version: 1
task_types:
  - DOCUMENT_SUMMARY
allowed_tools:
  - get_board_context
  - search_document_chunks
  - propose_board_actions
max_steps: 8
max_model_calls: 3
max_tool_calls: 8
---

# 目标

把选中的资料、摘录和卡片整理成简洁的摘要卡、要点卡和问题卡。

# 输出

只返回通过校验的画布动作。后端在完成归属与 schema 检查后，才会真正写入画布。
