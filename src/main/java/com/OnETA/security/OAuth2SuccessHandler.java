package com.OnETA.security;

import com.OnETA.dto.auth.SignupResponseDto;
import com.OnETA.dto.auth.TokenResponseDto;
import com.OnETA.entity.Role;
import com.OnETA.repository.UserRepository;
import com.OnETA.service.AuthService;
import com.OnETA.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final AuthService authService;
    private final UserRepository userRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        String email = CustomOAuth2UserService.requireVerifiedEmail((OAuth2User) authentication.getPrincipal());
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/api/token-test");
        boolean registered = userRepository.findByEmail(email)
                .filter(user -> user.getRole() == Role.USER).isPresent();
        if (registered) {
            TokenResponseDto tokens = authService.loginSocialUser(email);
            target.queryParam("accessToken", tokens.getAccessToken())
                    .queryParam("refreshToken", tokens.getRefreshToken());
        } else {
            SignupResponseDto signup = authService.startSocialSignup(email);
            target.queryParam("tempId", signup.tempId())
                    .queryParam("expiresInSeconds", signup.expiresInSeconds());
        }
        getRedirectStrategy().sendRedirect(request, response, target.build().encode().toUriString());
    }
}
