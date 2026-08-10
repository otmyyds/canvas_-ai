---
slug: image-generation
name: 图片生成技能
version: 1
task_types:
  - GENERATE_IMAGE
  - EDIT_IMAGE
  - GENERATE_COVER
  - GENERATE_DIAGRAM_ASSET
allowed_tools:
  - get_board_context
  - get_card_content
  - search_document_chunks
  - propose_image_prompt
  - create_image_asset
  - store_asset
  - attach_asset_to_card
max_steps: 8
max_model_calls: 3
max_tool_calls: 8
fallback_policy:
  on_image_failure: create_prompt_card
---

# 目标

根据用户提示词和当前画布上下文，生成或编辑图片资产。

# 必需流程

1. 根据用户提示、选中卡片和选中文件构建 ImagePromptSpec。
2. 校验意图、尺寸、质量和安全边界。
3. 使用配置的图片模型生成图片。
4. 将最终资产上传到 RustFS。
5. 仅在存储成功后创建或更新图片卡片。

# 兜底

如果图片生成在重试后仍失败，则创建一张提示词卡片，方便用户手动重试。
