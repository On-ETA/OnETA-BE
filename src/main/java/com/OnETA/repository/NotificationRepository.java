package com.OnETA.repository;

import com.OnETA.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findAllByUserId(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM notification_schedule_snapshots WHERE notification_id IN (:ids)", nativeQuery = true)
    int deleteScheduleSnapshotsByIds(@Param("ids") List<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM notification_deliveries WHERE notification_id IN (:ids)", nativeQuery = true)
    int deleteDeliveriesByIds(@Param("ids") List<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM notification_reminder_offsets WHERE notification_id IN (:ids)", nativeQuery = true)
    int deleteReminderOffsetsByIds(@Param("ids") List<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM arrival_notifications WHERE notification_id IN (:ids)", nativeQuery = true)
    int deleteArrivalRowsByIds(@Param("ids") List<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM notifications WHERE notification_id IN (:ids)", nativeQuery = true)
    int deleteRowsByIds(@Param("ids") List<Long> ids);
}
