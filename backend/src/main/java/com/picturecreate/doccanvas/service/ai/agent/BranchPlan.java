package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;

/**
 * Supervisor 拆解出的分支计划：一个根节点加若干互相独立的一级分支。
 * 每个分支只由自己的 Worker 展开，Worker 之间不共享目标以外的上下文。
 */
public record BranchPlan(String rootTitle, List<BranchSpec> branches) {
}
