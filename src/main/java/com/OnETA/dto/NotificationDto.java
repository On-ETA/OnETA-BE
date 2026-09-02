package com.OnETA.dto;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationScheduleType;
import com.OnETA.service.RepeatDaysService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.List;

public class NotificationDto {

    @Getter
    @Setter
    public static class CreateArrivalRequest {
        private String routeName;
        private LocalTime targetArrivalTime;
        private List<Integer> reminderOffsetMinutes;
        private List<String> repeatDays;
        private String routeDetails;
        private NotificationScheduleType scheduleType;
    }

    @Getter
    @Setter
    public static class UpdateArrivalRequest {
        private String routeName;
        private LocalTime targetArrivalTime;
        private List<Integer> reminderOffsetMinutes;
        private List<String> repeatDays;
        private String routeDetails;
        private NotificationScheduleType scheduleType;
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
        private List<Integer> reminderOffsetMinutes;
        private List<String> repeatDays;
        private String routeDetails;
        private Boolean isActive;
        private NotificationScheduleType scheduleType;

        public static ArrivalResponse fromEntity(ArrivalNotification entity, RepeatDaysService repeatDaysService) {
            return ArrivalResponse.builder()
                    .notificationId(entity.getId())
                    .routeName(entity.getName())
                    .targetArrivalTime(entity.getTargetArrivalTime())
                    .reminderOffsetMinutes(entity.getReminderOffsetMinutesList())
                    .repeatDays(repeatDaysService.toDays(entity.getRepeatDays()))
                    .routeDetails(entity.getRouteDetails())
                    .isActive(entity.getIsActive())
                    .scheduleType(entity.getScheduleType())
                    .build();
        }
    }

    @Getter
    @Builder
    public static class ArrivalDetailResponse {
        private Long notificationId;
        private String routeName;
        private LocalTime targetArrivalTime;
        private List<Integer> reminderOffsetMinutes;
        private List<String> repeatDays;
        private Boolean isActive;
        private TransitDto.RouteOptionResponse route;
        private NotificationScheduleType scheduleType;
    }
}
