package com.OnETA.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.firebase.enabled", havingValue = "true")
public class FirebaseConfig {
    private final ResourceLoader resourceLoader;
    @Value("${firebase.service-account:classpath:firebase-service-account.json}")
    private String serviceAccount;

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) return;
            var resource = resourceLoader.getResource(serviceAccount);

            // 파일 존재 여부 먼저 확인
            if (!resource.exists()) {
                throw new IllegalArgumentException("resources 폴더에서 firebase-service-account.json 파일을 찾을 수 없습니다.");
            }

            try (InputStream input = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(input)).build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase application has been initialized successfully!");
            }
        } catch (Exception e) {
            // 에러의 진짜 원인을 찾기 위해 전체 스택 트레이스를 출력
            log.error("Firebase 초기화 실패! 파일 위치나 JSON 구조를 다시 확인하세요.", e);
            // 초기화 실패 시 어차피 알림이 안 가므로 서버 구동을 중단
            throw new RuntimeException("Firebase 초기화 실패", e);
        }
    }
}
