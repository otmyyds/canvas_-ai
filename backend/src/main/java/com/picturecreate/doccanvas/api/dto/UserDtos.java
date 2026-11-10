package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    /** 用户资料：users 表中的可展示字段 + 画布数量 */
    public record UserProfileResponse(UUID id, String email, String displayName,
            Instant createdAt, Instant updatedAt, long boardCount) {
    }
}
