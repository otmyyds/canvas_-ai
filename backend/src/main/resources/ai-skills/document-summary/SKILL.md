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

只返回通过校验的画布动作（create_shape + connect）。后端在完成归属与 schema 检查后，才会真正写入画布。
