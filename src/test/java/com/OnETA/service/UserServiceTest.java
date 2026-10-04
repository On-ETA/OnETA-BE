package com.OnETA.service;

import com.OnETA.entity.Notification;
import com.OnETA.entity.User;
import com.OnETA.repository.EmailVerificationRepository;
import com.OnETA.repository.NotificationRepository;
import com.OnETA.repository.RefreshTokenRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import com.OnETA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock UserDeviceTokenRepository userDeviceTokenRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock EmailVerificationRepository emailVerificationRepository;

    @InjectMocks UserService userService;

    @Test
    void deleteUserRemovesNotificationChildrenBeforeUser() {
        User user = mock(User.class);
        Notification notification = mock(Notification.class);

        when(user.getId()).thenReturn(7L);
        when(notification.getId()).thenReturn(42L);
        when(userRepository.findForNotificationByEmail("user@example.com"))
                .thenReturn(Optional.of(user));
        when(notificationRepository.findAllByUserId(7L))
                .thenReturn(List.of(notification));
        when(refreshTokenRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.empty());
        when(emailVerificationRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.empty());

        userService.deleteUser("user@example.com");

        InOrder order = inOrder(notificationRepository, userRepository);
        order.verify(notificationRepository).deleteScheduleSnapshotsByIds(List.of(42L));
        order.verify(notificationRepository).deleteDeliveriesByIds(List.of(42L));
        order.verify(notificationRepository).deleteReminderOffsetsByIds(List.of(42L));
        order.verify(notificationRepository).deleteArrivalRowsByIds(List.of(42L));
        order.verify(notificationRepository).deleteRowsByIds(List.of(42L));
        order.verify(userRepository).deleteById(7L);

        verify(userDeviceTokenRepository).deleteAllByUserId(7L);
    }
}
