package com.OnETA.service;

import com.OnETA.common.exception.GlobalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.script.RedisScript;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingSignupStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final PendingSignupStore store = new PendingSignupStore(redis, 900);

    PendingSignupStoreTest() { when(redis.opsForValue()).thenReturn(values); }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void signupHasRedisTtlAndSingleUseId() {
        var response = store.create("test@example.com", "hash", "name");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        String key = "auth:pending-signup:" + response.tempId();
        var json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(key), json.capture(), eq(Duration.ofMinutes(15)));
        assertThat(json.getValue()).contains("GUEST", "hash");
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), eq(List.of(key)))).thenReturn(json.getValue(), (String) null);
        assertThat(store.consume(response.tempId()).email()).isEqualTo("test@example.com");
        assertThatThrownBy(() -> store.consume(response.tempId())).isInstanceOf(GlobalException.class);
    }

    @Test
    void rollbackRestoresConsumedIdWithoutExtendingExpiry() {
        var response = store.create("test@example.com", "hash", "name");
        var json = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), json.capture(), any(Duration.class));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList())).thenReturn(json.getValue());
        TransactionSynchronizationManager.initSynchronization();
        store.consume(response.tempId());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(values).setIfAbsent(anyString(), eq(json.getValue()), argThat(duration ->
                !duration.isNegative() && duration.compareTo(Duration.ofMinutes(15)) <= 0));
    }

    @Test
    void successfulCommitDoesNotRestoreId() {
        var response = store.create("test@example.com", null, "name");
        var json = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), json.capture(), any(Duration.class));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList())).thenReturn(json.getValue());
        TransactionSynchronizationManager.initSynchronization();
        store.consume(response.tempId());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void expiredAndMalformedIdsAreRejected() {
        assertThatThrownBy(() -> store.consume("invalid")).isInstanceOf(GlobalException.class);
        verify(redis, never()).execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList());
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList())).thenReturn(
                "{\"email\":\"test@example.com\",\"passwordHash\":null,\"nickname\":\"n\",\"role\":\"GUEST\",\"expiresAtMillis\":1}");
        assertThatThrownBy(() -> store.consume("12345678-1234-1234-1234-123456789abc"))
                .isInstanceOf(GlobalException.class);
    }
}
