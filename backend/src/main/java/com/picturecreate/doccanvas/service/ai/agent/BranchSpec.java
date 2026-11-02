package com.picturecreate.doccanvas.service.ai.agent;

/**
 * 单个分支的拆解结果。
 * goal 必须自包含：Worker 只收到根标题、分支标题、分支目标和全局约束。
 */
public record BranchSpec(String key, String title, String goal, int maxChildren) {
}
