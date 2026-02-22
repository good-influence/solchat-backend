package com.sol.solchat.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

@Slf4j
@Component
public class JwtTokenUtil {

    @Value("${spring.jwt.secret.key}")
    private String secretKey;

    // 토큰 만료 시간 (1시간 = 3600000ms)
//    private final long EXPIRATION_TIME = 1000L * 60 * 60;
    private final long EXPIRATION_TIME = 1000000L * 60 * 60;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    // 사용자 정보 기반으로 JWT 토큰 생성
    public String generateToken(String username, Long userId) {
        Instant nowInstant = Instant.now();
        Instant expiryInstant = nowInstant.plus(Duration.ofMillis(EXPIRATION_TIME));

        Date now = Date.from(nowInstant);
        Date expiryDate = Date.from(expiryInstant);

        return Jwts.builder()
                .setSubject(username)
                .claim("userId", userId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // 토큰에서 사용자 이름(subject) 추출
    public String getUsernameFromToken(String token) {
        try {
            return extractClaim(token, Claims::getSubject);
        } catch (JwtException e) {
            log.warn("JWT 토큰 파싱 실패: {}", e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("JWT 토큰이 비어있거나 잘못되었습니다.");
            return null;
        }
    }

    // 토큰에서 Long Id 추출
    public Long getUserIdFromToken(String token) {
        try {
            return extractClaim(token, claims -> claims.get("userId", Long.class));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT userId 클레임 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token);
            return true;
        }catch (SecurityException | MalformedJwtException e) {
            log.warn("잘못된 JWT 서명입니다.");
        } catch (ExpiredJwtException e) {
            log.warn("만료된 JWT 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            log.warn("지원되지 않는 JWT 토큰입니다.");
        } catch (IllegalArgumentException e) {
            log.warn("JWT 토큰이 잘못되었습니다.");
        }
        return false;
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
