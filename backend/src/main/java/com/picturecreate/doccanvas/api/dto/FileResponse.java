package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.FileParseStatus;
import com.picturecreate.doccanvas.domain.SourceFile;

public record FileResponse(
        UUID id,
        UUID boardId,
        String storageKey,
        String filename,
        String mimeType,
        long size,
        String textExcerpt,
        FileParseStatus parseStatus,
        Instant createdAt) {

    public static FileResponse from(SourceFile file) {
        return new FileResponse(file.getId(), file.getBoardId(), file.getStorageKey(), file.getFilename(),
                file.getMimeType(), file.getSize(), file.getTextExcerpt(), file.getParseStatus(), file.getCreatedAt());
    }
}
