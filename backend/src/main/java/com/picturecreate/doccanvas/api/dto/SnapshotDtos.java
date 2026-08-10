package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.BoardSnapshot;

public final class SnapshotDtos {

    private SnapshotDtos() {
    }

    public record SnapshotRequest(String snapshotJson) {
    }

    public record SnapshotResponse(UUID id, UUID boardId, String snapshotJson, int version, Instant createdAt) {
        public static SnapshotResponse from(BoardSnapshot snapshot) {
            return new SnapshotResponse(snapshot.getId(), snapshot.getBoardId(), snapshot.getSnapshotJson(),
                    snapshot.getVersion(), snapshot.getCreatedAt());
        }
    }
}
