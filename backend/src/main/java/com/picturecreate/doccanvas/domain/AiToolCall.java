package com.picturecreate.doccanvas.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_tool_calls")
public class AiToolCall extends BaseEntity {

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "input_json", nullable = false, columnDefinition = "text")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "text")
    private String outputJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

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
