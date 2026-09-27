package com.OnETA.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ErrorCode;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.MulticastMessage;
import com.OnETA.entity.User;
import com.OnETA.entity.UserDeviceToken;
import com.OnETA.repository.UserRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Collection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@Service
@Slf4j
public class FcmPushService {

    private final ResourceLoader resourceLoader;
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;

    @Value("${firebase.service-account:}")
    private String serviceAccount;

    @Value("${app.firebase.enabled:false}")
    private boolean firebaseEnabled = true;

    private Clock clock = Clock.systemUTC();

    public FcmPushService(ResourceLoader resourceLoader, UserRepository userRepository, UserDeviceTokenRepository userDeviceTokenRepository) {
        this.resourceLoader = resourceLoader;
        this.userRepository = userRepository;
        this.userDeviceTokenRepository = userDeviceTokenRepository;
    }

    @PostConstruct
    void initialize() {
        if (!firebaseEnabled) return;
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

    public void sendPush(String targetEmail, String title, String body) {
        if (!firebaseEnabled) {
            log.debug("Firebase is disabled; skipping FCM push. Email: {}", targetEmail);
            return;
        }

        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("[FCM 발송 실패] Firebase 서비스 계정이 설정되지 않았습니다.");
            return;
        }

        User user = userRepository.findByEmail(targetEmail).orElse(null);

        if (user == null || user.getDeviceTokens() == null || user.getDeviceTokens().isEmpty()) {
            log.warn("[FCM 발송 실패] 타겟 유저가 없거나 등록된 기기(토큰)가 없습니다. Email: {}", targetEmail);
            return;
        }

        java.util.List<UserDeviceToken> deviceTokens = user.getDeviceTokens();
        java.util.List<String> tokens = deviceTokens.stream()
                .map(UserDeviceToken::getDeviceToken)
                .toList();

        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();

        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(notification)
                .build();

        try {
            com.google.firebase.messaging.BatchResponse response = FirebaseMessaging.getInstance().sendMulticast(message);

            log.info("[FCM 다중 발송 완료] Email: {}, 총 시도: {}, 성공: {}, 실패: {}",
                    targetEmail, tokens.size(), response.getSuccessCount(), response.getFailureCount());

            if (response.getFailureCount() > 0) {
                log.warn("[FCM 발송 실패 기기 존재] 일부 기기로의 발송이 실패했습니다.");
                java.util.List<com.google.firebase.messaging.SendResponse> responses = response.getResponses();
                java.util.List<UserDeviceToken> invalidTokens = new java.util.ArrayList<>();
                
                for (int i = 0; i < responses.size(); i++) {
                    if (!responses.get(i).isSuccessful()) {
                        MessagingErrorCode errorCode = responses.get(i).getException().getMessagingErrorCode();
                        if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                            invalidTokens.add(deviceTokens.get(i));
                        }
                    }
                }
                
                if (!invalidTokens.isEmpty()) {
                    userDeviceTokenRepository.deleteAll(invalidTokens);
                    log.info("[FCM 유효하지 않은 토큰 삭제] Email: {}, 삭제된 토큰 수: {}", targetEmail, invalidTokens.size());
                }
            }
        } catch (Exception e) {
            log.error("[FCM 다중 발송 에러] Email: {}, Reason: {}", targetEmail, e.getMessage());
        }
    }

    public void sendPushMessage(String deviceToken, String title, String body) {
        sendPushMessage(deviceToken, title, body, null);
    }

    public void sendPushMessage(String deviceToken, String title, String body,
                                java.time.LocalDateTime hardDeadlineAt) {
        if (!firebaseEnabled) {
            throw new FcmPushException("FIREBASE_DISABLED", "Firebase 발송이 비활성화되어 있습니다.", true, null);
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                throw new FcmPushException(
                        "FIREBASE_CONFIGURATION",
                        "Firebase 서비스 계정이 설정되지 않았습니다.", true, null);
            }

            Message.Builder messageBuilder = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build());
            if (hardDeadlineAt != null) {
                long remainingMillis = Duration.between(
                        clock.instant(), hardDeadlineAt.toInstant(ZoneOffset.UTC)).toMillis();
                if (remainingMillis <= 0) {
                    throw new FcmPushException(
                            "DELIVERY_EXPIRED", "알림 발송 유효 시간이 지났습니다.", true, null);
                }
                messageBuilder.setAndroidConfig(AndroidConfig.builder()
                        .setTtl(remainingMillis)
                        .build());
                messageBuilder.setWebpushConfig(WebpushConfig.builder()
                        .putHeader("TTL", Long.toString((remainingMillis + 999) / 1000)).build());
                messageBuilder.setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-expiration",
                                String.valueOf(hardDeadlineAt.toInstant(ZoneOffset.UTC).getEpochSecond()))
                        .setAps(Aps.builder().build())
                        .build());
            }

            Message message = messageBuilder.build();
            com.OnETA.common.ExternalApiCallCounter.record("FCM", "send");
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM push sent. messageId={}", messageId);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode errorCode = e.getMessagingErrorCode();
            // Firebase Admin SDK 9.4.3 does not distinguish token errors from other
            // INVALID_ARGUMENT validation errors structurally, so uncertainty stays retryable.
            boolean invalidToken = errorCode == MessagingErrorCode.UNREGISTERED
                    || (errorCode == MessagingErrorCode.INVALID_ARGUMENT && isTokenError(e.getMessage()));
            boolean permanent = invalidToken
                    || errorCode == MessagingErrorCode.SENDER_ID_MISMATCH
                    || errorCode == MessagingErrorCode.THIRD_PARTY_AUTH_ERROR;
            ErrorCode platformErrorCode = e.getErrorCode();
            String code = errorCode != null
                    ? errorCode.name()
                    : platformErrorCode == null ? "FCM_ERROR" : platformErrorCode.name();
            throw new FcmPushException(
                    code, e.getMessage(), permanent, parseRetryAfter(e), e);
        } catch (FcmPushException e) {
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FcmPushException("PROGRAMMING_ERROR", e.getMessage(), true, e);
        } catch (Exception e) {
            throw new FcmPushException("FCM_ERROR", e.getMessage(), false, e);
        }
    }

    private Duration parseRetryAfter(FirebaseMessagingException exception) {
        IncomingHttpResponse response = exception.getHttpResponse();
        if (response == null || response.getHeaders() == null) return null;

        Object rawValue = response.getHeaders().entrySet().stream()
                .filter(entry -> "retry-after".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (rawValue instanceof Collection<?> values) {
            rawValue = values.stream().findFirst().orElse(null);
        }
        if (rawValue == null) return null;

        String value = String.valueOf(rawValue).trim();
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Retry-After may also be an HTTP-date. Do not inspect human error messages.
        }

        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Duration.between(clock.instant(), retryAt).isNegative()
                    ? Duration.ZERO
                    : Duration.between(clock.instant(), retryAt);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean isTokenError(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase();
        return normalized.contains("registration token")
                || normalized.contains("token is invalid")
                || normalized.contains("invalid registration");
    }
}
