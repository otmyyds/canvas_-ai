package com.picturecreate.doccanvas.common;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

/**
 * JWT 签发与解析（JJWT 0.12.6）。
 * 密钥从环境变量 JWT_SECRET 读取，必须是 64 位十六进制字符串（32 字节）；
 * 缺失或格式错误时应用启动失败，不使用任何内置默认密钥。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** accessToken 过期：30 分钟 */
    private static final long ACCESS_EXPIRATION_MS = 1000 * 60 * 30;
    /** refreshToken 过期：7 天 */
    private static final long REFRESH_EXPIRATION_MS = 1000L * 60 * 60 * 24 * 7;
    /** refreshToken 的类型标记，防止把 accessToken 当刷新令牌使用 */
    private static final String REFRESH_TYPE = "refresh";

    private final SecretKey key;

    public JwtService(@Value("${JWT_SECRET:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 JWT_SECRET：请配置 64 位十六进制字符串（32 字节）");
        }
        byte[] keyBytes;
        try {
            keyBytes = hexStringToByteArray(secret.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("环境变量 JWT_SECRET 格式错误：必须是十六进制字符串", exception);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("环境变量 JWT_SECRET 长度不足：HS256 至少需要 32 字节（64 位十六进制）");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(UUID userId, String email) {
        return generateToken(Map.of("userId", userId.toString(), "email", email), ACCESS_EXPIRATION_MS);
    }

    public String generateRefreshToken(UUID userId) {
        return generateToken(Map.of("userId", userId.toString(), "type", REFRESH_TYPE), REFRESH_EXPIRATION_MS);
    }

    private String generateToken(Map<String, ?> claims, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析 token；无效/过期返回 null */
    private Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException | SignatureException | MalformedJwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 accessToken 的用户 id：必须验签通过、未过期且不是 refreshToken。
     * 防止有效期更长的 refreshToken 被当作访问令牌调用业务接口。
     */
    public UUID parseAccessToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null || claims.get("type") != null) {
            return null;
        }
        return getUserId(claims);
    }

    /**
     * 从 refreshToken 解析用户 id：必须验签通过、未过期且 type=refresh。
     * 刷新流程只信任这里解析出的 id，不接受客户端额外提交的用户标识。
     */
    public UUID parseRefreshToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null || !REFRESH_TYPE.equals(claims.get("type", String.class))) {
            return null;
        }
        return getUserId(claims);
    }

    private UUID getUserId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object value = claims.get("userId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("非法十六进制字符");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }
}
