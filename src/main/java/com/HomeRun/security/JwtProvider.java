package com.HomeRun.security;

import com.HomeRun.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtProvider {

    @Value("${jwt.secret}")
    private String secretKeyString;

    @Value("${jwt.access-expiration}")
    private long accessTokenValidTime;

    @Value("${jwt.refresh-expiration}")
    private long refreshTokenValidTime;

    private Key secretKey;

    // 객체 초기화 시 비밀키를 안전한 Key 객체로 변환합니다.
    @PostConstruct
    protected void init() {
        secretKey = Keys.hmacShaKeyFor(secretKeyString.getBytes());
    }

    // JWT 토큰 생성 메서드
    public String createAccessToken(String email, String role) {
        Claims claims = Jwts.claims().setSubject(email); // 토큰의 주인을 이메일로 설정
        claims.put("role", role); // 권한 정보 추가

        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims) // 정보 저장
                .setIssuedAt(now) // 토큰 발행 시간
                .setExpiration(new Date(now.getTime() + accessTokenValidTime)) // 토큰 만료 시간
                .signWith(secretKey, SignatureAlgorithm.HS256) // 암호화 알고리즘과 비밀키 셋팅
                .compact();
    }

    // 2. Refresh Token 생성 (권한 정보 불필요, 이메일만으로 식별)
    public String createRefreshToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + refreshTokenValidTime))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    // 토큰에서 이메일 추출
    public String getEmailFromToken(String token) {
        return Jwts.parserBuilder().setSigningKey(secretKey).build()
                .parseClaimsJws(token).getBody().getSubject();
    }

    // 토큰의 유효성 및 만료일자 확인
    public boolean validateToken(String token) {
        try {
            Jws<Claims> claims = Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token);
            return !claims.getBody().getExpiration().before(new Date()); // 만료 기간이 안 지났으면 true
        } catch (Exception e) {
            return false; // 위조되었거나 만료된 토큰이면 false
        }
    }
}