package com.picturecreate.doccanvas.common;

import java.io.IOException;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 从 Authorization: Bearer <token> 解析用户并写入 SecurityContext。
 * 认证用户 principal 为 userId（UUID），用户身份只来自验签后的 token。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (request.getParameter("token") != null && !request.getParameter("token").isBlank()) {
            // 图片/资源直链：<img src> 无法带 header，用 query param 传 token
            token = request.getParameter("token");
        }
        if (token != null) {
            UUID userId = jwtService.parseAccessToken(token);
            if (userId != null) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
