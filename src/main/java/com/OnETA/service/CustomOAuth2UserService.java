package com.OnETA.service;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User user = super.loadUser(userRequest);
        requireVerifiedEmail(user);
        return user;
    }

    public static String requireVerifiedEmail(OAuth2User user) {
        String email = user.getAttribute("email");
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(user.getAttribute("email_verified"))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_email"),
                    "인증된 소셜 이메일이 필요합니다.");
        }
        return email;
    }
}
