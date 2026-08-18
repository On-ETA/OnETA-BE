package com.HomeRun.service;

import com.HomeRun.entity.Notification;
import com.HomeRun.entity.NotificationDelivery;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.NotificationDeliveryRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDeliveryServiceTest {

    @Test
    void sentDeliveryIsNotDispatchedAgain() {
        NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        ArrivalNotificationRepository notifications = mock(ArrivalNotificationRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        FcmPushService fcm = mock(FcmPushService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(null);

        NotificationDelivery delivery = new NotificationDelivery(
                mock(Notification.class), LocalDate.of(2026, 8, 10), "token", "title", "body");
        ReflectionTestUtils.setField(delivery, "id", 1L);
        when(deliveries.findAllByStatusIn(any())).thenReturn(List.of(delivery));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));

        NotificationDeliveryService service = new NotificationDeliveryService(
                deliveries, notifications, tokens, fcm, transactionManager);

        service.processPending();
        service.processPending();

        verify(fcm, times(1)).sendPushMessage("token", "title", "body");
        verify(deliveries, times(2)).save(delivery);
    }
}
