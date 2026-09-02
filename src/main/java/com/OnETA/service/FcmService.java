package com.OnETA.service;

import com.OnETA.entity.User;
import com.OnETA.repository.UserRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

    private final UserRepository userRepository;

    // 사용자 이메일을 기반으로 FCM 토큰을 찾아 푸시 알림을 발송
    public void sendPush(String targetEmail, String title, String body) {
        // 유저 조회 및 토큰 검증
        User user = userRepository.findByEmail(targetEmail).orElse(null);

        if (user == null || user.getFcmToken() == null || user.getFcmToken().isEmpty()) {
            log.warn("[FCM 발송 실패] 타겟 유저가 없거나 FCM 토큰이 없습니다. Email: {}", targetEmail);
            return;
        }

        // 알림 메시지 조립
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();

        Message message = Message.builder()
                .setToken(user.getFcmToken()) // 유저의 스마트폰 기기 토큰 지정
                .setNotification(notification)
                .build();

        // 발송
        try {
            String response = FirebaseMessaging.getInstance().send(message);
            log.info("[FCM 발송 성공] Email: {}, MessageID: {}", targetEmail, response);
        } catch (Exception e) {
            log.error("[FCM 발송 실패] Email: {}, Reason: {}", targetEmail, e.getMessage());
        }
    }
}