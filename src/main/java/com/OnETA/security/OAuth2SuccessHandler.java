package com.OnETA.security;

import com.OnETA.common.exception.GlobalException;
import com.OnETA.entity.*;
import com.OnETA.repository.UserRepository;
import com.OnETA.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

import static com.OnETA.common.error.ErrorCode.USER_NOT_FOUND;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        // 1. 구글 로그인에 성공한 사용자 정보 가져오기
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String email = oAuth2User.getAttribute("email");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(USER_NOT_FOUND));

        // 2. 해당 사용자의 이메일로 access Token, refresh Token 생성
        String accessToken = jwtProvider.createAccessToken(email, user.getRole().getKey());
        String refreshToken = jwtProvider.createRefreshToken(email);

        // 구글 가입자도 DB에 리프레시 토큰 저장 필요
        authService.saveOrUpdateRefreshToken(email, refreshToken);

        String targetUrl;

        if (user.getRole() == Role.GUEST) { // 신규 구글 가입자 -> 약관 동의 화면 이동
            targetUrl = "https://on-eta.com/signup/consent";
        } else { // 기존 회원 (USER) -> 홈 화면 이동
            targetUrl = "https://on-eta.com/home";
        }

        // 3. 토큰을 가지고 우리가 원하는 엔드포인트로 리다이렉트 (URL 파라미터 2개 전송)
        String finalUrl = UriComponentsBuilder.fromUriString(targetUrl)
                .queryParam("accessToken", accessToken)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, finalUrl);
    }
}