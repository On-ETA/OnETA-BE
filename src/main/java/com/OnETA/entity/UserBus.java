package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "user_buses")
public class UserBus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String routeId; // 공공데이터포털 노선 ID (ex: 100100112)

    @Column(nullable = false)
    private String busNumber; // 버스 번호 (ex: 144, N62, 마포09)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BusDirection direction; // 방면

    @Column(nullable = false)
    private String directionName; // 맞춤 알림 리스트에서 보여줄 방면 텍스트 (예: "강남역 방면")

    @OneToOne(mappedBy = "userBus", cascade = CascadeType.ALL, orphanRemoval = true)
    private DepotNotification depotNotification;

    @Builder
    public UserBus(User user, String routeId, String busNumber, BusDirection direction, String directionName) {
        this.user = user;
        this.routeId = routeId;
        this.busNumber = busNumber;
        this.direction = direction;
        this.directionName = directionName;
    }
}