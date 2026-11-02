package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;

/**
 * 单个分支的执行结果。
 * children 是分支下的一级子节点标题；失败与中断由 error/aborted 区分。
 */
public record BranchResult(String key, String title, List<String> children, String error, boolean aborted) {

    public static BranchResult ok(BranchSpec spec, List<String> children) {
        return new BranchResult(spec.key(), spec.title(), List.copyOf(children), null, false);
    }

    public static BranchResult failed(BranchSpec spec, String error) {
        return new BranchResult(spec.key(), spec.title(), List.of(), error, false);
    }

    public static BranchResult aborted(BranchSpec spec) {
        return new BranchResult(spec.key(), spec.title(), List.of(), "任务被用户中断", true);
    }

    public boolean ok() {
        return error == null && !aborted;
    }
}
