package com.OnETA.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FcmPushServiceTest {
    @Test
    void scheduledPushWithDeadlineBuildsValidMessageAndReachesFirebase() throws Exception {
        var service = new FcmPushService(
                new DefaultResourceLoader(),
                mock(com.OnETA.repository.UserRepository.class),
                mock(com.OnETA.repository.UserDeviceTokenRepository.class));
        var now = Instant.parse("2026-09-27T07:00:00Z");
        ReflectionTestUtils.setField(service, "firebaseEnabled", true);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(now, ZoneOffset.UTC));
        var app = mock(FirebaseApp.class);
        var messaging = mock(FirebaseMessaging.class);

        // Keep SDK message builders real; isolate initialization and network delivery only.
        try (var apps = mockStatic(FirebaseApp.class);
             var firebase = mockStatic(FirebaseMessaging.class)) {
            apps.when(FirebaseApp::getApps).thenReturn(List.of(app));
            firebase.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.send(any(Message.class))).thenReturn("test-message-id");

            service.sendPushMessage("device-token", "Reminder", "Departure soon",
                    LocalDateTime.ofInstant(now.plusSeconds(60), ZoneOffset.UTC));

            verify(messaging).send(any(Message.class));
        }
    }
}
