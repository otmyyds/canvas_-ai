package com.picturecreate.doccanvas.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称过长") String displayName,
            @NotBlank(message = "密码不能为空") @Size(min = 6, max = 64, message = "密码长度需在 6-64 位") String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record AuthResponse(
            String token,
            String refreshToken,
            UserInfo user) {
    }

    public record UserInfo(
            UUID id,
            String email,
            String displayName) {
    }
}
