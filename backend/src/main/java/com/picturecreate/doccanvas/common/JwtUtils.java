package com.picturecreate.doccanvas.common;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

/**
 * JWT 工具类（JJWT 0.12.6）。
 * 本地开发用固定密钥，生产应由环境变量注入。
 */
public final class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    /** 固定开发密钥（64 字符 hex，HS256 需要 >= 32 字节） */
    private static final String HEX_SECRET = "d73ffd9a8fb93133a3788fba3f4b2bc5518e1289c92346ea78eafee794140d05";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(hexStringToByteArray(HEX_SECRET));

    /** accessToken 过期：30 分钟 */
    private static final long ACCESS_EXPIRATION_MS = 1000 * 60 * 30;
    /** refreshToken 过期：7 天 */
    private static final long REFRESH_EXPIRATION_MS = 1000L * 60 * 60 * 24 * 7;

    private JwtUtils() {
    }

    public static String generateAccessToken(UUID userId, String email) {
        return generateToken(Map.of("userId", userId.toString(), "email", email), ACCESS_EXPIRATION_MS);
    }

    public static String generateRefreshToken(UUID userId) {
        return generateToken(Map.of("userId", userId.toString(), "type", "refresh"), REFRESH_EXPIRATION_MS);
    }

    public static String generateToken(Map<String, ?> claims, long expirationMs) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析 token；无效/过期返回 null */
    public static Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException | SignatureException | MalformedJwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    public static UUID getUserId(Claims claims) {
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

    public static String getEmail(Claims claims) {
        return claims == null ? null : claims.get("email", String.class);
    }

    private static byte[] hexStringToByteArray(String hex) {
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
