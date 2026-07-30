package com.HomeRun.service;

import org.springframework.stereotype.Service;

@Service
public class FcmPushService {

    // 임시 목업: FCM 푸시 메시지 발송
    public void sendPushMessage(String deviceToken, String title, String body) {
        // 실제 구현에서는 Firebase Admin SDK를 사용하여 메시지를 전송합니다.
        System.out.println("==================================================");
        System.out.println("푸시 알림 전송 (FCM Mock)");
        System.out.println("Token: " + deviceToken);
        System.out.println("Title: " + title);
        System.out.println("Body:  " + body);
        System.out.println("==================================================");
    }
}
