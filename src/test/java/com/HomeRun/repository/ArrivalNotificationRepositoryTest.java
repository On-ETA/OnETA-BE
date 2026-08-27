package com.HomeRun.repository;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.NotificationScheduleType;
import com.HomeRun.entity.Role;
import com.HomeRun.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ArrivalNotificationRepositoryTest {

    @Autowired
    private ArrivalNotificationRepository notifications;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void schedulerQueryInitializesReminderOffsetsAfterRepositoryTransactionEnds() {
        Long notificationId = transactionTemplate.execute(status -> {
            User user = users.save(new User(
                    "lazy-offset-" + System.nanoTime() + "@example.com",
                    "password", "tester", Role.USER));
            ArrivalNotification notification = new ArrivalNotification(
                    user, "first-transit", List.of(5, 15, 30), 127,
                    LocalTime.of(18, 0), "route", NotificationScheduleType.FIRST_TRANSIT);
            return notifications.saveAndFlush(notification).getId();
        });

        ArrivalNotification loaded = notifications.findAllByIsActiveTrue().stream()
                .filter(notification -> notification.getId().equals(notificationId))
                .findFirst()
                .orElseThrow();

        assertThat(loaded.getReminderOffsetMinutesList()).containsExactly(5, 15, 30);
    }
}
