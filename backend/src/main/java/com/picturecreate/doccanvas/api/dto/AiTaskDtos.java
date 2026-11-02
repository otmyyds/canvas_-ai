package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;
import com.picturecreate.doccanvas.domain.AiTaskStep;
import com.picturecreate.doccanvas.domain.AiTaskStepStatus;
import com.picturecreate.doccanvas.domain.AiTaskType;

public final class AiTaskDtos {

    private AiTaskDtos() {
    }

    public record AiTaskResponse(
            UUID id,
            UUID boardId,
            AiTaskType type,
            String prompt,
            AiTaskStatus status,
            int progress,
            String currentStep,
            boolean abortRequested,
            String resultJson,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            List<AiTaskStepResponse> steps) {

        public static AiTaskResponse from(AiTask task, List<AiTaskStep> steps) {
            return new AiTaskResponse(task.getId(), task.getBoardId(), task.getType(), task.getPrompt(),
                    task.getStatus(), task.getProgress(), task.getCurrentStep(), task.isAbortRequested(),
                    task.getResultJson(), task.getErrorMessage(), task.getStartedAt(), task.getFinishedAt(),
                    task.getCreatedAt(), steps.stream().map(AiTaskStepResponse::from).toList());
        }
    }

    public record AiTaskStepResponse(
            UUID id,
            int seq,
            AiTaskType stepType,
            String title,
            String description,
            AiTaskStepStatus status,
            int progress,
            String message,
            String resultMessage,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {

        public static AiTaskStepResponse from(AiTaskStep step) {
            return new AiTaskStepResponse(step.getId(), step.getSeq(), step.getStepType(), step.getTitle(),
                    step.getDescription(), step.getStatus(), step.getProgress(), step.getMessage(),
                    step.getResultMessage(), step.getErrorMessage(), step.getStartedAt(), step.getFinishedAt(),
                    step.getCreatedAt());
        }
    }
}
