package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.auth.SignupResponseDto;
import com.OnETA.entity.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.List;

@Service
public class PendingSignupStore {
    private static final String PREFIX = "auth:pending-signup:";
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local value = redis.call('GET', KEYS[1]); "
                    + "if value then redis.call('DEL', KEYS[1]); end; return value", String.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public PendingSignupStore(StringRedisTemplate redis,
                              @Value("${auth.signup.ttl-seconds:900}") long ttlSeconds) {
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("auth.signup.ttl-seconds must be positive");
        }
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public SignupResponseDto create(String email, String passwordHash, String nickname) {
        String tempId = UUID.randomUUID().toString();
        PendingSignup signup = new PendingSignup(email, passwordHash, nickname, Role.GUEST,
                System.currentTimeMillis() + ttl.toMillis());
        redis.opsForValue().set(PREFIX + tempId, mapper.writeValueAsString(signup), ttl);
        afterRollback(() -> redis.delete(PREFIX + tempId));
        return new SignupResponseDto(tempId, ttl.toSeconds());
    }

    // Atomic GET + DEL also works on Redis versions without the GETDEL command.
    // A failed database transaction restores it only for the original remaining lifetime.
    public PendingSignup consume(String tempId) {
        if (tempId == null || !tempId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalidSignup();
        }
        String key = PREFIX + tempId;
        String value = redis.execute(CONSUME, List.of(key));
        if (value == null) throw invalidSignup();
        PendingSignup signup = mapper.readValue(value, PendingSignup.class);
        if (signup.role() != Role.GUEST || signup.expiresAtMillis() <= System.currentTimeMillis()) {
            throw invalidSignup();
        }
        afterRollback(() -> {
            long remaining = signup.expiresAtMillis() - System.currentTimeMillis();
            if (remaining > 0) redis.opsForValue().setIfAbsent(key, value, Duration.ofMillis(remaining));
        });
        return signup;
    }

    private static GlobalException invalidSignup() {
        return new GlobalException(ErrorCode.INVALID_INPUT_VALUE,
                "임시 가입 정보가 만료되었거나 유효하지 않습니다. 이메일 인증부터 다시 진행해 주세요.");
    }

    private void afterRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) action.run();
                }
            });
        }
    }

    public record PendingSignup(String email, String passwordHash, String nickname,
                                Role role, long expiresAtMillis) {
    }
}
