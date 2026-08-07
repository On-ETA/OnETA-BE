package com.HomeRun.service;

import com.HomeRun.entity.*;
import com.HomeRun.repository.UserRepository;
import com.HomeRun.util.NicknameGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // 1. 구글로부터 기본 사용자 정보를 받아옵니다.
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 2. 구글이 넘겨준 데이터(이름, 이메일 등)를 Map 형태로 추출합니다.
        Map<String, Object> attributes = oAuth2User.getAttributes();
        String email = (String) attributes.get("email");

        // 3. 우리 DB에 이 이메일이 있는지 확인합니다.
        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isEmpty()) {
            // 4. DB에 없다면 최초 회원가입을 진행합니다.
            User newUser = User.builder()
                    .email(email)
                    .nickname(NicknameGenerator.generate())
                    .role(Role.GUEST)
                    .build();

            userRepository.save(newUser); // DB에 쏙 저장합니다.
        }

        return oAuth2User;
    }



}
