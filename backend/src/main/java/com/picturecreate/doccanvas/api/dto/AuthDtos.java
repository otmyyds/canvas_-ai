package com.picturecreate.doccanvas.api.dto;

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

    /** 刷新访问令牌：只提交 refreshToken，用户身份由后端验签解析 */
    public record RefreshRequest(@NotBlank(message = "刷新令牌不能为空") String refreshToken) {
    }

    public record AuthResponse(
            String token,
            String refreshToken,
            UserInfo user) {
    }

    /** 登录用户展示信息（不返回内部用户 id） */
    public record UserInfo(
            String email,
            String displayName) {
    }
}
