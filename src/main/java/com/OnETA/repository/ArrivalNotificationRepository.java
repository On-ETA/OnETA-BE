package com.OnETA.repository;

import com.OnETA.entity.ArrivalNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArrivalNotificationRepository extends JpaRepository<ArrivalNotification, Long> {
    @org.springframework.data.jpa.repository.Query("select n from ArrivalNotification n where n.user.id = :userId and n.transitArchived = false")
    List<ArrivalNotification> findAllByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    // Current read after acquiring the user lock, even under MySQL REPEATABLE READ.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select n from ArrivalNotification n where n.user.id = :userId and n.transitArchived = false")
    List<ArrivalNotification> findAllForDuplicateCheckByUserId(
            @org.springframework.data.repository.query.Param("userId") Long userId);
    
    // 알림 활성화되어 있는 항목들 조회 (스케줄러 용도)
    @EntityGraph(attributePaths = "reminderOffsetMinutes")
    @org.springframework.data.jpa.repository.Query("select n from ArrivalNotification n where n.isActive = true and n.transitArchived = false")
    List<ArrivalNotification> findAllByIsActiveTrue();
}
