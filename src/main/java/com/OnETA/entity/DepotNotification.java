package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "depot_notifications")
public class DepotNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 등록된 버스 중 어떤 버스에 대한 알림인지 연결 (1:1 관계)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_bus_id", nullable = false)
    private UserBus userBus;

    @Column(nullable = false)
    private boolean active; // 알림 활성화 여부

    @Builder
    public DepotNotification(UserBus userBus, boolean active) {
        this.userBus = userBus;
        this.active = active;
    }

    // 알림 켜기
    public void enableNotification() {
        this.active = true;
    }

    // 알림 끄기 (1회 발송 후 자동 호출)
    public void disableNotification() {
        this.active = false;
    }
}