package com.picturecreate.doccanvas.service.ai;

/**
 * 一次画布动作应用的实际生效结果。
 * skipped 表示模型引用了不存在或不属于本画布的节点；计划执行器据此判定步骤失败，
 * 不再静默跳过。
 */
public record ApplyReport(int createdShapes, int createdEdges, int updatedShapes, int deletedShapes,
        int skipped) {

    public static ApplyReport empty() {
        return new ApplyReport(0, 0, 0, 0, 0);
    }

    /** 真正改变画布的节点数（新建、更新、删除） */
    public int changedShapes() {
        return createdShapes + updatedShapes + deletedShapes;
    }
}
