package com.OnETA.service;

import com.OnETA.entity.*;
import com.OnETA.repository.UserRepository;
import com.OnETA.util.NicknameGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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

        if (userOptional.isPresent()) {
            User user = userOptional.get();

            // 일반 회원가입 유저인지 확인 (비밀번호 존재 여부로 판단)
            if (user.getPassword() != null && !user.getPassword().isEmpty()) {

                OAuth2Error oauth2Error = new OAuth2Error(
                        "invalid_provider",
                        "이미 등록된 이메일입니다. 이메일/비밀번호로 로그인해주세요.",
                        null
                );

                // 예외를 발생시켜 구글 로그인 흐름 강제 중단
                throw new OAuth2AuthenticationException(oauth2Error, oauth2Error.toString());
            }
            // 구글 가입자가 다시 접근한 경우 예외가 발생하지 않으므로 if문을 빠져나가고,
            // 아래의 else문을 건너뛰어 중복 가입을 방지한 뒤 로그인 처리 됨
        } else {
            // DB에 없다면 최초 회원가입(구글 연동)을 진행
            User newUser = User.builder()
                    .email(email)
                    .nickname(NicknameGenerator.generate())
                    .role(Role.GUEST)
                    .build();

            userRepository.save(newUser);
        }

        return oAuth2User;
    }



}
