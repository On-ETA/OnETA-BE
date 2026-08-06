package com.HomeRun.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@Service
@Slf4j
public class FcmPushService {

    private final ResourceLoader resourceLoader;

    @Value("${firebase.service-account:}")
    private String serviceAccount;

    public FcmPushService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void initialize() {
        if (serviceAccount == null || serviceAccount.isBlank() || !FirebaseApp.getApps().isEmpty()) return;
        try {
            Resource resource = resourceLoader.getResource(serviceAccount);
            try (InputStream inputStream = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(inputStream))
                        .build();
                FirebaseApp.initializeApp(options);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Firebase 서비스 계정을 초기화할 수 없습니다.", e);
        }
    }

    public void sendPushMessage(String deviceToken, String title, String body) {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new IllegalStateException("Firebase 서비스 계정이 설정되지 않았습니다.");
        }
        Message message = Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .build();
        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM push sent. messageId={}", messageId);
        } catch (Exception e) {
            throw new IllegalStateException("FCM 푸시 발송에 실패했습니다.", e);
        }
    }
}
