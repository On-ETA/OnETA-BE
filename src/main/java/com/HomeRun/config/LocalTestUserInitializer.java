package com.HomeRun.config;

import com.HomeRun.entity.Role;
import com.HomeRun.entity.User;
import com.HomeRun.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the fixed account used for local Swagger/E2E testing.
 * This initializer is deliberately limited to the local Spring profile.
 */
@Component
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class LocalTestUserInitializer implements CommandLineRunner {

    public static final String EMAIL = "oneta.local.test@example.com";
    public static final String PASSWORD = "OnetaTest#2026";
    private static final String NICKNAME = "OnETA Local";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(EMAIL).isPresent()) {
            return;
        }

        userRepository.save(User.builder()
                .email(EMAIL)
                .password(passwordEncoder.encode(PASSWORD))
                .nickname(NICKNAME)
                .role(Role.USER)
                .build());
        log.info("Created local test user: {}", EMAIL);
    }
}
