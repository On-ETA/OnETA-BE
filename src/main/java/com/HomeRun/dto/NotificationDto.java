package com.HomeRun.dto;

import com.HomeRun.entity.ArrivalNotification;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

public class NotificationDto {

    @Getter
    @Setter
    public static class CreateArrivalRequest {
        private String routeName;
        private LocalTime targetArrivalTime;
        private Integer reminderOffsetMinutes;
        private String repeatDays;
        private String routeDetails;
    }

    @Getter
    @Setter
    public static class UpdateArrivalRequest {
        private String routeName;
        private LocalTime targetArrivalTime;
        private Integer reminderOffsetMinutes;
        private String repeatDays;
        private String routeDetails;
    }

    @Getter
    @Setter
    public static class ToggleStatusRequest {
        private Boolean isActive;
    }

    @Getter
    @Builder
    public static class ArrivalResponse {
        private Long notificationId;
        private String routeName;
        private LocalTime targetArrivalTime;
        private Integer reminderOffsetMinutes;
        private String repeatDays;
        private String routeDetails;
        private Boolean isActive;

        public static ArrivalResponse fromEntity(ArrivalNotification entity) {
            return ArrivalResponse.builder()
                    .notificationId(entity.getId())
                    .routeName(entity.getName())
                    .targetArrivalTime(entity.getTargetArrivalTime())
                    .reminderOffsetMinutes(entity.getReminderOffsetMinutes())
                    .repeatDays(entity.getRepeatDays())
                    .routeDetails(entity.getRouteDetails())
                    .isActive(entity.getIsActive())
                    .build();
        }
    }
}
