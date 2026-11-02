package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.UUID;

/**
 * 一次画布动作应用的实际生效结果。
 * skipped 表示模型引用了不存在或不属于本画布的节点；计划执行器据此判定步骤失败，
 * 不再静默跳过。createdNodes 供任务完成后写入会话摘要，便于下一轮指代节点。
 */
public record ApplyReport(int createdShapes, int createdEdges, int updatedShapes, int deletedShapes,
        int skipped, List<CreatedShape> createdNodes) {

    public static ApplyReport empty() {
        return new ApplyReport(0, 0, 0, 0, 0, List.of());
    }

    /** 真正改变画布的节点数（新建、更新、删除） */
    public int changedShapes() {
        return createdShapes + updatedShapes + deletedShapes;
    }

    /** 新建节点的真实 id、标题与层级 */
    public record CreatedShape(UUID id, String title, int level) {
    }
}
