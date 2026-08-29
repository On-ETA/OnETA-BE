package com.OnETA.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        // 1. 클라이언트의 요청 헤더에서 토큰을 추출합니다. (보통 "Authorization: Bearer 토큰값" 형태로 옵니다)
        String header = request.getHeader("Authorization");
        String token = null;

        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7); // "Bearer " 이후의 진짜 토큰 값만 잘라냅니다.
        }

        // 2. 토큰이 존재하고, 유효성 검사를 통과한다면
        if (token != null && jwtProvider.validateToken(token)) {
            // 토큰에서 이메일을 꺼냅니다.
            String email = jwtProvider.getEmailFromToken(token);

            // 이 사용자가 인증되었다는 증명서를 Spring Security Context에 강제로 집어넣습니다.
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 3. 다음 필터로 요청을 넘깁니다.
        filterChain.doFilter(request, response);
    }
}