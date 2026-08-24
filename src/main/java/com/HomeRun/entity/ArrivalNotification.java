package com.HomeRun.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

@Entity
@DiscriminatorValue("ARRIVAL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "arrival_notifications")
public class ArrivalNotification extends Notification {

    @Column(name = "target_arrival_time", nullable = false)
    private LocalTime targetArrivalTime;

    @Column(name = "route_details", columnDefinition = "TEXT")
    private String routeDetails;

    @Column(name = "first_station_id")
    private String firstStationId;

    @Column(name = "first_route_id")
    private String firstRouteId;

    @Column(name = "target_boarding_time")
    private LocalTime targetBoardingTime;

    public ArrivalNotification(User user, String name, Integer reminderOffsetMinutes, Integer repeatDays,
                               LocalTime targetArrivalTime, String routeDetails) {
        super(user, name, reminderOffsetMinutes, repeatDays);
        this.targetArrivalTime = targetArrivalTime;
        this.routeDetails = routeDetails;
    }

    public ArrivalNotification(User user, String name, List<Integer> reminderOffsetMinutes, Integer repeatDays,
                               LocalTime targetArrivalTime, String routeDetails) {
        super(user, name, reminderOffsetMinutes, repeatDays);
        this.targetArrivalTime = targetArrivalTime;
        this.routeDetails = routeDetails;
    }

    public void updateArrivalInfo(LocalTime targetArrivalTime, String routeDetails) {
        if (targetArrivalTime != null) this.targetArrivalTime = targetArrivalTime;
        if (routeDetails != null) this.routeDetails = routeDetails;
    }

    public void updateTrackingInfo(String firstStationId, String firstRouteId, LocalTime targetBoardingTime) {
        this.firstStationId = firstStationId;
        this.firstRouteId = firstRouteId;
        this.targetBoardingTime = targetBoardingTime;
    }
}
