package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * AI 工具调用审计实体，对应 ai_tool_calls 表。
 * 记录每次 AI 只读工具调用（get_board_context 等）的入参/出参/耗时/状态，
 * 便于排查和审计。当前工具调用未真正启用，但审计表已建。
 */
@Entity
@Table(name = "ai_tool_calls")
public class AiToolCall extends BaseEntity {

    /** 所属任务 id */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 工具名（如 get_board_context / list_source_files） */
    @Column(name = "tool_name", nullable = false)
    private String toolName;

    /** 工具入参 JSON */
    @Column(name = "input_json", nullable = false, columnDefinition = "text")
    private String inputJson;

    /** 工具出参 JSON */
    @Column(name = "output_json", columnDefinition = "text")
    private String outputJson;

    /** 调用状态：RUNNING/SUCCESS/FAILED */
    @Column(nullable = false)
    private String status;

    /** 失败原因 */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** 调用耗时（毫秒） */
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    protected AiToolCall() {
    }

    public AiToolCall(UUID taskId, String toolName, String inputJson, String status) {
        this.taskId = taskId;
        this.toolName = toolName;
        this.inputJson = inputJson;
        this.status = status;
    }

    public void succeed(String outputJson, long durationMs) {
        this.outputJson = outputJson;
        this.status = "SUCCESS";
        this.durationMs = durationMs;
    }

    public void fail(String message, long durationMs) {
        this.errorMessage = message;
        this.status = "FAILED";
        this.durationMs = durationMs;
    }
}
