package com.OnETA.security;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException, ServletException {
        log.error("OAuth2 Login Failed: {}", exception.getMessage());

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_CONFLICT);

        String errorMessage = "소셜 로그인에 실패했습니다.";

        // CustomOAuth2UserService에서 던진 에러 메시지 추출
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            errorMessage = oauth2Exception.getError().getDescription();
        }

        // 한글 에러 메시지 인코딩
        String encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);

        // 에러 메시지 담을 쿠키 생성 (이름: "oauth2_auth_error")
        Cookie errorCookie = new Cookie("oauth2_auth_error", encodedErrorMessage);
        errorCookie.setPath("/"); // 모든 경로에서 쿠키 접근 가능
        errorCookie.setMaxAge(60); // 60초 후 자동 삭제

        errorCookie.setSecure(true);

        // Response에 쿠키 추가
        response.addCookie(errorCookie);

        // 리디렉션할 메인 로그인 화면 URL 설정 및 클라이언트를 해당 URL로 강제 이동
        String targetUrl = "https://on-eta.com";
        response.sendRedirect(targetUrl);
    }
}