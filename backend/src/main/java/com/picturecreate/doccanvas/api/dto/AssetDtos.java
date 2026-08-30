package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.Asset;

public final class AssetDtos {

    private AssetDtos() {
    }

    public record AssetResponse(
            UUID id,
            UUID boardId,
            String mimeType,
            Integer width,
            Integer height,
            Instant createdAt) {

        public static AssetResponse from(Asset asset) {
            return new AssetResponse(asset.getId(), asset.getBoardId(), asset.getMimeType(), asset.getWidth(),
                    asset.getHeight(), asset.getCreatedAt());
        }
    }
}
