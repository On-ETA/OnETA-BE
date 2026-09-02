package com.OnETA.config;

import com.OnETA.entity.Role;
import com.OnETA.entity.User;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발 환경에서 이메일 인증 없이 API를 테스트할 수 있는 계정을 준비한다.
 * local 프로필에서만 빈으로 등록되므로 dev/prod 환경에는 계정이 생성되지 않는다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DevUserInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${dev-user.enabled:true}")
    private boolean enabled;

    @Value("${dev-user.email:dev@homerun.local}")
    private String email;

    @Value("${dev-user.password:Dev1234!}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || userRepository.findByEmail(email).isPresent()) return;

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .nickname("개발자")
                .role(Role.USER)
                .build();
        userRepository.save(user);

        // 비밀번호는 로그에 남기지 않는다. 기본값 또는 로컬 환경변수 값을 사용한다.
        log.info("Local development user created: {}", email);
    }
}
