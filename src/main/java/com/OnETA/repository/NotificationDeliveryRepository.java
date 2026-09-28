package com.OnETA.repository;

import com.OnETA.entity.NotificationDelivery;
import com.OnETA.entity.NotificationDeliveryStatus;
import com.OnETA.entity.DeliveryPhase;
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
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("""
            update NotificationDelivery d set d.status = com.OnETA.entity.NotificationDeliveryStatus.EXPIRED,
                d.nextAttemptAt = null, d.lastErrorCode = 'TRANSIT_REPLACED',
                d.lastErrorMessage = '첫차·막차 알림이 교체 또는 삭제되었습니다.'
            where d.notification.id in :ids and d.status in (
                com.OnETA.entity.NotificationDeliveryStatus.PENDING,
                com.OnETA.entity.NotificationDeliveryStatus.SENDING)
            """)
    int expireReplacedTransitDeliveries(@Param("ids") List<Long> notificationIds);

    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDate(Long notificationId, LocalDate deliveryDate);
    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDateAndReminderOffsetMinutes(
            Long notificationId, LocalDate deliveryDate, int reminderOffsetMinutes);
    Optional<NotificationDelivery> findByNotificationIdAndDeliveryDateAndReminderOffsetMinutesAndDeliveryPhase(
            Long notificationId, LocalDate deliveryDate, int reminderOffsetMinutes, DeliveryPhase deliveryPhase);
    boolean existsByNotificationIdAndDeliveryDateAndDeliveryPhase(
            Long notificationId, LocalDate deliveryDate, DeliveryPhase deliveryPhase);

    List<NotificationDelivery> findAllByStatusIn(List<NotificationDeliveryStatus> statuses);

    @Query("""
            select d from NotificationDelivery d
            where (d.status = com.OnETA.entity.NotificationDeliveryStatus.PENDING
                   and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)
                   and :now < d.hardDeadlineAt)
               or (d.status = com.OnETA.entity.NotificationDeliveryStatus.SENDING
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
                    com.OnETA.entity.NotificationDeliveryStatus.PENDING,
                    com.OnETA.entity.NotificationDeliveryStatus.SENDING)
              and d.hardDeadlineAt <= :now
            order by d.id
            """)
    List<NotificationDelivery> findExpiredCandidates(
            @Param("now") LocalDateTime now, Pageable pageable);
}
