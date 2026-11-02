package com.picturecreate.doccanvas.service.ai.llm;

/** 任务超出模型调用、工具调用、时长或 token 预算 */
public class TaskBudgetExceededException extends RuntimeException {

    public TaskBudgetExceededException(String message) {
        super(message);
    }
}
