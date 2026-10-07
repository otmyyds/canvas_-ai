package com.picturecreate.doccanvas.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class CurrentUserService {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserService.class);

    private final UserAccountRepository userAccountRepository;

    public CurrentUserService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional
    public CurrentUser resolve(HttpServletRequest request) {
        // 优先：JWT 认证用户
        CurrentUser authenticated = authenticatedUser();
        if (authenticated != null) {
            log.debug("通过 JWT 解析用户：userId={}", authenticated.id());
            return authenticated;
        }
        // 兼容：请求头（本地开发 / 旧客户端）
        String email = headerOrDefault(request, "X-User-Email", "demo@example.com");
        String displayName = headerOrDefault(request, "X-User-Name", "演示用户");
        UserAccount user = userAccountRepository.findByEmail(email)
                .orElseGet(() -> userAccountRepository.save(new UserAccount(email, displayName)));
        log.debug("通过请求头解析用户：userId={} email={}", user.getId(), email);
        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName());
    }

    public CurrentUser demoUser() {
        UserAccount user = userAccountRepository.findByEmail("demo@example.com")
                .orElseGet(() -> userAccountRepository.save(new UserAccount("demo@example.com", "演示用户")));
        return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName());
    }

    /**
     * 从 SecurityContext 读取 JWT 认证用户（principal 为 userId）。
     * 不加 @Transactional：private 方法上的注解不会被 Spring 代理识别（且它只被 resolve() 调用，
     * 实际运行在 resolve() 的事务里，加在这里纯属误导）。
     */
    private CurrentUser authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof UUID userId)) {
            return null;
        }
        return userAccountRepository.findById(userId)
                .map(user -> new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName()))
                .orElse(null);
    }

    private String headerOrDefault(HttpServletRequest request, String header, String fallback) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        // 前端对非 ASCII 展示名做了 encodeURIComponent，这里还原
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }
}
