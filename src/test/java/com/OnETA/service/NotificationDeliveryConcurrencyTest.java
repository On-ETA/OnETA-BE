package com.OnETA.service;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationDelivery;
import com.OnETA.entity.NotificationDeliveryStatus;
import com.OnETA.entity.Role;
import com.OnETA.entity.User;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.repository.NotificationDeliveryRepository;
import com.OnETA.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationDeliveryConcurrencyTest {

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private ArrivalNotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void simultaneousProcessingAllowsOnlyOneClaim() throws Exception {
        User user = userRepository.save(new User(
                "claim-race-" + System.nanoTime() + "@example.com", "password", "tester", Role.USER));
        ArrivalNotification notification = notificationRepository.saveAndFlush(
                new ArrivalNotification(user, "race", 0, 21, LocalTime.of(18, 30), "route"));
        NotificationDelivery delivery = new NotificationDelivery(
                notification, LocalDate.of(2026, 8, 10), "race-token-" + System.nanoTime(),
                "title", "body", LocalDateTime.now(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        ReflectionTestUtils.setField(delivery, "attempts", 3);
        delivery = deliveryRepository.saveAndFlush(delivery);
        Long deliveryId = delivery.getId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        var first = callers.submit(() -> processWhenReleased(start, deliveryId));
        var second = callers.submit(() -> processWhenReleased(start, deliveryId));
        start.countDown();

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        callers.shutdownNow();

        NotificationDelivery result = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(result.getAttempts()).isEqualTo(4);
        assertThat(result.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
    }

    private void processWhenReleased(CountDownLatch start, Long deliveryId) {
        try {
            start.await();
            deliveryService.processDelivery(deliveryId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
