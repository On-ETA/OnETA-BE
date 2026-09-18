package com.OnETA.service;

import com.OnETA.entity.*;
import com.OnETA.repository.*;
import com.OnETA.common.exception.GlobalException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushTestServiceTest {
    @Test void sendsOnlyAuthenticatedUsersTokenAndRateLimitsRepeat() {
        var users = mock(UserRepository.class); var tokens = mock(UserDeviceTokenRepository.class);
        var push = mock(FcmPushService.class); var user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        when(tokens.findByUserId(1L)).thenReturn(Optional.of(new UserDeviceToken(user, "own-token")));
        var service = new PushTestService(users, tokens, push);
        assertThat(service.send("me")).isEqualTo("FCM_ACCEPTED");
        verify(push).sendPushMessage(eq("own-token"), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.send("me")).isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> service.send("other")).isInstanceOf(GlobalException.class);
        verifyNoMoreInteractions(push);
    }
    @Test void disabledFirebaseCannotBeRecordedAsSuccessfulSend() {
        var push = new FcmPushService(new DefaultResourceLoader());
        ReflectionTestUtils.setField(push, "firebaseEnabled", false);
        assertThatThrownBy(() -> push.sendPushMessage("token", "test", "body"))
                .isInstanceOfSatisfying(FcmPushException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("FIREBASE_DISABLED"));
    }
}
