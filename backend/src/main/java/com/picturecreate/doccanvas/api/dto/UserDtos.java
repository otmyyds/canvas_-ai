package com.picturecreate.doccanvas.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    /** 用户资料：users 表中的可展示字段 + 画布数量（不返回内部用户 id） */
    public record UserProfileResponse(String email, String displayName,
            Instant createdAt, Instant updatedAt, long boardCount) {
    }

    /** 修改基本资料：昵称和登录邮箱 */
    public record UpdateProfileRequest(
            @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称过长") String displayName,
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email) {
    }

    /** 修改密码：校验当前密码，新密码长度与注册规则一致 */
    public record ChangePasswordRequest(
            @NotBlank(message = "当前密码不能为空") String currentPassword,
            @NotBlank(message = "新密码不能为空") @Size(min = 6, max = 64, message = "密码长度需在 6-64 位") String newPassword) {
    }
}
