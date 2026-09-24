package com.OnETA.service;

import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest)
            throws OAuth2AuthenticationException {
        OAuth2User oauthUser = super.loadUser(userRequest);
        String email = requireVerifiedEmail(oauthUser);

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getPassword() != null && !user.getPassword().isEmpty()) {
                OAuth2Error error = new OAuth2Error(
                        "invalid_provider",
                        "이미 등록된 이메일입니다. 이메일/비밀번호로 로그인해주세요.",
                        null
                );

                throw new OAuth2AuthenticationException(
                        error, error.getDescription()
                );
            }
        });

        // 신규 사용자는 여기서 DB에 저장하지 않음
        return oauthUser;
    }

    public static String requireVerifiedEmail(OAuth2User user) {
        String email = user.getAttribute("email");

        if (email == null || email.isBlank()
                || !Boolean.TRUE.equals(user.getAttribute("email_verified"))) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_email"),
                    "인증된 소셜 이메일이 필요합니다."
            );
        }

        return email;
    }
}