package com.HomeRun.service;

import com.HomeRun.entity.User;
import com.HomeRun.entity.UserDeviceToken;
import com.HomeRun.repository.UserDeviceTokenRepository;
import com.HomeRun.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                .isInstanceOf(IllegalArgumentException.class);
        verify(current, never()).updateToken(anyString());
        verify(tokens, never()).delete(any());
    }
}
