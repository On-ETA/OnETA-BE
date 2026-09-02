package com.OnETA.repository;

import com.OnETA.entity.ArrivalNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArrivalNotificationRepository extends JpaRepository<ArrivalNotification, Long> {
    List<ArrivalNotification> findAllByUserId(Long userId);
    
    // 알림 활성화되어 있는 항목들 조회 (스케줄러 용도)
    @EntityGraph(attributePaths = "reminderOffsetMinutes")
    List<ArrivalNotification> findAllByIsActiveTrue();
}
