package com.OnETA.repository;

import com.OnETA.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;

public interface ScheduleSnapshotRepository extends JpaRepository<ScheduleSnapshot, Long> {
    Optional<ScheduleSnapshot> findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
            Long notificationId, LocalDate serviceDate, NotificationScheduleType type, String routeHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduleSnapshot s where s.notification.id = :notificationId and s.serviceDate = :serviceDate and s.scheduleType = :type and s.routeHash = :routeHash")
    Optional<ScheduleSnapshot> findForUpdate(@Param("notificationId") Long notificationId, @Param("serviceDate") LocalDate serviceDate,
                                             @Param("type") NotificationScheduleType type, @Param("routeHash") String routeHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduleSnapshot s where s.id = :id")
    Optional<ScheduleSnapshot> findByIdForUpdate(@Param("id") Long id);
}
