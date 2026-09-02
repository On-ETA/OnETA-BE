package com.OnETA.repository;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationDelivery;
import com.OnETA.entity.NotificationDeliveryStatus;
import com.OnETA.entity.Role;
import com.OnETA.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class NotificationDeliveryRepositoryTest {

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private ArrivalNotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void queryReturnsOnlyProcessableDeliveriesAndExcludesFreshSending() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime timeoutThreshold = now.minusMinutes(5);
        User user = userRepository.save(new User(
                "delivery-query@example.com", "password", "tester", Role.USER));

        NotificationDelivery freshSending = saveDelivery(user, 1L, NotificationDeliveryStatus.SENDING,
                now.minusMinutes(1), null);
        NotificationDelivery timedOutSending = saveDelivery(user, 2L, NotificationDeliveryStatus.SENDING,
                timeoutThreshold, null);
        NotificationDelivery recoverableSending = saveDelivery(user, 3L, NotificationDeliveryStatus.SENDING,
                null, null);
        NotificationDelivery futurePending = saveDelivery(user, 4L, NotificationDeliveryStatus.PENDING,
                null, now.plusMinutes(1));
        NotificationDelivery duePending = saveDelivery(user, 5L, NotificationDeliveryStatus.PENDING,
                null, now.minusMinutes(1));
        NotificationDelivery newPending = saveDelivery(user, 6L, NotificationDeliveryStatus.PENDING,
                null, null);
        NotificationDelivery sent = saveDelivery(user, 7L, NotificationDeliveryStatus.SENT,
                null, null);
        NotificationDelivery failed = saveDelivery(user, 8L, NotificationDeliveryStatus.FAILED,
                null, null);

        List<NotificationDelivery> result = deliveryRepository.findProcessable(
                now, timeoutThreshold, PageRequest.of(0, 100));

        assertThat(result).extracting(NotificationDelivery::getId)
                .containsExactly(timedOutSending.getId(), recoverableSending.getId(),
                        duePending.getId(), newPending.getId());
        assertThat(result).extracting(NotificationDelivery::getId)
                .doesNotContain(freshSending.getId(), futurePending.getId(), sent.getId(), failed.getId());
    }

    @Test
    void processablePendingIsNotBlockedByOneHundredFreshSendingRows() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime timeoutThreshold = now.minusMinutes(5);
        User user = userRepository.save(new User(
                "delivery-batch@example.com", "password", "tester", Role.USER));

        for (int i = 0; i < 100; i++) {
            saveDelivery(user, 100L + i, NotificationDeliveryStatus.SENDING,
                    now.minusMinutes(1), null);
        }
        NotificationDelivery processablePending = saveDelivery(user, 999L, NotificationDeliveryStatus.PENDING,
                null, now.minusMinutes(1));

        List<NotificationDelivery> result = deliveryRepository.findProcessable(
                now, timeoutThreshold, PageRequest.of(0, 100));

        assertThat(result).extracting(NotificationDelivery::getId)
                .containsExactly(processablePending.getId());
    }

    private NotificationDelivery saveDelivery(User user, long marker,
                                               NotificationDeliveryStatus status,
                                               LocalDateTime lastAttemptAt,
                                               LocalDateTime nextAttemptAt) {
        ArrivalNotification notification = notificationRepository.saveAndFlush(
                new ArrivalNotification(user, "notification-" + marker + "-" + System.nanoTime(),
                        0, 21, LocalTime.of(18, 30), "route"));
        NotificationDelivery delivery = new NotificationDelivery(
                notification, LocalDate.of(2026, 8, 10), "token-" + marker + "-" + System.nanoTime(),
                "title", "body", LocalDateTime.of(2026, 8, 10, 8, 59),
                LocalDateTime.of(2026, 8, 10, 9, 9));
        ReflectionTestUtils.setField(delivery, "status", status);
        ReflectionTestUtils.setField(delivery, "lastAttemptAt", lastAttemptAt);
        ReflectionTestUtils.setField(delivery, "nextAttemptAt", nextAttemptAt);
        return deliveryRepository.saveAndFlush(delivery);
    }
}
