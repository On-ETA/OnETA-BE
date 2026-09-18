package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.repository.UserRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PushTestService {
    private final UserRepository users;
    private final UserDeviceTokenRepository tokens;
    private final FcmPushService push;
    private final ConcurrentHashMap<Long, Instant> sent = new ConcurrentHashMap<>();

    public String send(String email) {
        var user = users.findByEmail(email).orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
        var token = tokens.findByUserId(user.getId()).orElseThrow(() -> new GlobalException(
                ErrorCode.INVALID_INPUT_VALUE, "등록된 디바이스 토큰이 없습니다. 사이트 알림을 허용한 후 새로고침해주세요."));
        Instant now = Instant.now();
        sent.entrySet().removeIf(e -> e.getValue().isBefore(now.minusSeconds(60)));
        if (sent.putIfAbsent(user.getId(), now) != null)
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "테스트 푸시는 1분에 한 번 보낼 수 있습니다.");
        try {
            push.sendPushMessage(token.getDeviceToken(), "온에타 테스트 알림", "푸시 수신 확인용 알림입니다.",
                    LocalDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC));
        } catch (FcmPushException e) {
            throw new GlobalException(ErrorCode.PUSH_UNAVAILABLE, "테스트 푸시 발송 실패: " + e.getErrorCode());
        }
        return "FCM_ACCEPTED";
    }
}
