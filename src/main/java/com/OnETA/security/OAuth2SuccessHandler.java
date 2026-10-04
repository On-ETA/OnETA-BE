package com.OnETA.security;

import com.OnETA.dto.auth.SignupResponseDto;
import com.OnETA.dto.auth.TokenResponseDto;
import com.OnETA.entity.Role;
import com.OnETA.repository.UserRepository;
import com.OnETA.service.AuthService;
import com.OnETA.service.CustomOAuth2UserService;
import com.OnETA.service.OAuth2CodeStore;
import com.OnETA.util.CookieUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final OAuth2CodeStore oAuth2CodeStore;
    private final CookieAuthorizationRequestRepository cookieAuthorizationRequestRepository;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        String email = CustomOAuth2UserService.requireVerifiedEmail(
                (OAuth2User) authentication.getPrincipal()
        );

        boolean registered = userRepository.findByEmail(email)
                .filter(user -> user.getRole() == Role.USER)
                .isPresent();

        Map<String, Object> data = new HashMap<>();

        if (registered) {
            TokenResponseDto tokens = authService.loginSocialUser(email);
            data.put("accessToken", tokens.getAccessToken());
            data.put("refreshToken", tokens.getRefreshToken());
        } else {
            SignupResponseDto signup = authService.startSocialSignup(email);
            data.put("tempId", signup.tempId());
            // 프론트 요청 구조 (필요시 expiresInSeconds 포함 가능)
        }

        String code = oAuth2CodeStore.generateCode(data);
        String targetUrl = determineTargetUrl(request, response, code);

        // 쿠키 클리어
        cookieAuthorizationRequestRepository.removeAuthorizationRequestCookies(request, response);

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response, String code) {
        Optional<String> redirectUri = CookieUtils.getCookie(request, CookieAuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE_NAME)
                .map(Cookie::getValue);

        String targetUrl = getDefaultTargetUrl();
        if (redirectUri.isPresent()) {
            String uri = redirectUri.get();
            // 기본 검증 (허용된 URI인지 확인 - 여기서는 oneta://oauth/callback 과 기타 웹 주소 허용 예시)
            if (uri.startsWith("oneta://oauth/callback") || uri.startsWith("https://on-eta.com") || uri.startsWith("https://api.on-eta.com") || uri.startsWith("http://localhost")) {
                targetUrl = uri;
            }
        } else {
             // 기본 웹 환경을 위한 폴백
             if (request.getServerName().equals("localhost")) {
                 targetUrl = "http://localhost:8080/swagger-ui/index.html";
             } else {
                 targetUrl = "https://on-eta.com/auth/callback";
             }
        }

        return UriComponentsBuilder.fromUriString(targetUrl)
                .queryParam("code", code)
                .build().toUriString();
    }
}
