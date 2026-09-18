package com.OnETA.service;

import com.OnETA.entity.User;
import com.OnETA.entity.UserDeviceToken;
import com.OnETA.repository.UserDeviceTokenRepository;
import com.OnETA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.error.ErrorCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeviceTokenServiceTest {

    @Test
    void tokenAlreadyOwnedByAnotherUserIsRejectedWithoutUpdatingCurrentToken() {
        UserRepository users = mock(UserRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        User user = mock(User.class);
        UserDeviceToken current = mock(UserDeviceToken.class);
        UserDeviceToken other = mock(UserDeviceToken.class);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(current.getId()).thenReturn(10L);
        when(other.getId()).thenReturn(20L);
        when(tokens.findByUserId(1L)).thenReturn(Optional.of(current));
        when(tokens.findByDeviceToken("already-used")).thenReturn(Optional.of(other));

        DeviceTokenService service = new DeviceTokenService(tokens, users);

        assertThatThrownBy(() -> service.registerOrUpdateToken("user@example.com", "already-used"))
                .isInstanceOfSatisfying(GlobalException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DEVICE_TOKEN_CONFLICT));
        verify(current, never()).updateToken(anyString());
        verify(tokens, never()).delete(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "token with spaces"})
    void invalidTokenIsRejectedBeforeDatabaseAccess(String token) {
        UserRepository users = mock(UserRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        assertThatThrownBy(() -> new DeviceTokenService(tokens, users).registerOrUpdateToken("user@example.com", token))
                .isInstanceOf(GlobalException.class);
        verifyNoInteractions(users, tokens);
    }

    @Test
    void firstRegistrationCannotStealAnotherUsersToken() {
        UserRepository users = mock(UserRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokens.findByDeviceToken("used")).thenReturn(Optional.of(mock(UserDeviceToken.class)));
        assertThatThrownBy(() -> new DeviceTokenService(tokens, users).registerOrUpdateToken("user@example.com", "used"))
                .isInstanceOfSatisfying(GlobalException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DEVICE_TOKEN_CONFLICT));
        verify(tokens, never()).delete(any());
        verify(tokens, never()).save(any());
    }

    @Test
    void repeatedRegistrationAndRefreshUpdateSingleExistingRecord() {
        UserRepository users = mock(UserRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        User user = mock(User.class);
        UserDeviceToken current = new UserDeviceToken(user, "old");
        org.springframework.test.util.ReflectionTestUtils.setField(current, "id", 10L);
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokens.findByUserId(1L)).thenReturn(Optional.of(current));
        when(tokens.findByDeviceToken("old")).thenReturn(Optional.of(current));
        DeviceTokenService service = new DeviceTokenService(tokens, users);
        service.registerOrUpdateToken("user@example.com", "old");
        service.registerOrUpdateToken("user@example.com", "new");
        assertThat(current.getDeviceToken()).isEqualTo("new");
        verify(tokens, never()).save(any());
    }
}
