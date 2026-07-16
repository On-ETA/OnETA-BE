package com.HomeRun.security;

import com.HomeRun.entity.*;
import com.HomeRun.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        // 1. 구글 로그인에 성공한 사용자 정보 가져오기
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        User user = userRepository.findByEmail(email).orElseThrow();

        // 2. 해당 사용자의 이메일로 access Token, refresh Token 생성
        String accessToken = jwtProvider.createAccessToken(email, user.getRole().getKey());
        String refreshToken = jwtProvider.createRefreshToken(email);

        // 3. 토큰을 가지고 우리가 원하는 엔드포인트(혹은 앱의 스킴)로 리다이렉트
        // 구글 가입자도 DB에 리프레시 토큰 저장 필요 (AuthService 의존성 주입 등 활용 권장, 여기서는 생략된 구조를 직접 구현해야 할 수 있음.
        // 간단하게 바로 저장하도록 Service에 넘기는 방식을 추천합니다.)
        // 클라이언트로 리다이렉트 (임시로 URL 파라미터 2개 전송)
        String targetUrl = "/api/token-test?accessToken=" + accessToken + "&refreshToken=" + refreshToken;

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}