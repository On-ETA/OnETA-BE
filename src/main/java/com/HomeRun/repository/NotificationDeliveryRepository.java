package com.HomeRun.repository;

import com.HomeRun.entity.NotificationDelivery;
import com.HomeRun.entity.NotificationDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDate(Long notificationId, LocalDate deliveryDate);

    List<NotificationDelivery> findAllByStatusIn(List<NotificationDeliveryStatus> statuses);
}
