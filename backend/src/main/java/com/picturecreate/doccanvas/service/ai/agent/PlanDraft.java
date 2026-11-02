package com.picturecreate.doccanvas.service.ai.agent;

/**
 * 预检模型产出的单步计划草稿。
 * type 复用 AiTaskType；description 面向用户，brief 是本步可独立执行的指令。
 */
public record PlanDraft(String type, String title, String description, String brief) {
}
