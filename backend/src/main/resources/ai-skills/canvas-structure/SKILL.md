---
slug: canvas-structure
name: 画布结构技能
version: 1
task_types:
  - CANVAS_STRUCTURE
allowed_tools:
  - get_board_context
  - get_card_content
  - propose_layout
  - propose_board_actions
max_steps: 8
max_model_calls: 3
max_tool_calls: 8
---

# 目标

把现有卡片整理成分组、关系与布局建议，且不破坏用户已有编辑。
