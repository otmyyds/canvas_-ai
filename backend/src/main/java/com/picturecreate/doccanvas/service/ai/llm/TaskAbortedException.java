package com.picturecreate.doccanvas.service.ai.llm;

/** 用户中断任务：在安全检查点抛出，由任务服务标记 ABORTED */
public class TaskAbortedException extends RuntimeException {

    public TaskAbortedException() {
        super("任务已被用户中断");
    }
}
