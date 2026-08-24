package com.HomeRun.repository;

import com.HomeRun.entity.NotificationDelivery;
import com.HomeRun.entity.NotificationDeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDate(Long notificationId, LocalDate deliveryDate);
    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDateAndReminderOffsetMinutes(
            Long notificationId, LocalDate deliveryDate, int reminderOffsetMinutes);

    List<NotificationDelivery> findAllByStatusIn(List<NotificationDeliveryStatus> statuses);

    @Query("""
            select d from NotificationDelivery d
            where (d.status = com.HomeRun.entity.NotificationDeliveryStatus.PENDING
                   and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)
                   and :now < d.hardDeadlineAt)
               or (d.status = com.HomeRun.entity.NotificationDeliveryStatus.SENDING
                   and (d.lastAttemptAt is null or d.lastAttemptAt <= :sendingTimeoutThreshold)
                   and :now < d.hardDeadlineAt)
            order by d.id
            """)
    List<NotificationDelivery> findProcessable(
            @Param("now") LocalDateTime now,
            @Param("sendingTimeoutThreshold") LocalDateTime sendingTimeoutThreshold,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from NotificationDelivery d where d.id = :id")
    Optional<NotificationDelivery> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select d from NotificationDelivery d
            where d.status in (
                    com.HomeRun.entity.NotificationDeliveryStatus.PENDING,
                    com.HomeRun.entity.NotificationDeliveryStatus.SENDING)
              and d.hardDeadlineAt <= :now
            order by d.id
            """)
    List<NotificationDelivery> findExpiredCandidates(
            @Param("now") LocalDateTime now, Pageable pageable);
}
