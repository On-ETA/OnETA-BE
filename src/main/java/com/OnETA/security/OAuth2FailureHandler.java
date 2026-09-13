package com.OnETA.security;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

        // 공통 응답 객체 생성
        ApiResponse<Void> apiResponse = ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE.getCode(), errorMessage);

        // JSON 변환 후 클라이언트로 출력
        ObjectMapper objectMapper = new ObjectMapper();
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}