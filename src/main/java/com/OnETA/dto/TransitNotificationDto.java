package com.OnETA.dto;

import com.OnETA.entity.NotificationCategory;
import com.OnETA.entity.NotificationScheduleType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

public class TransitNotificationDto {
    @Getter
    @Setter
    @JsonIgnoreProperties({"routeName", "repeatDays", "targetArrivalTime"})
    public static class CreateRequest {
        private List<Integer> reminderOffsetMinutes;
        private String routeDetails;
        @Schema(allowableValues = {"FIRST_TRANSIT", "LAST_TRANSIT"}, requiredMode = Schema.RequiredMode.REQUIRED)
        private NotificationScheduleType scheduleType;

        public NotificationDto.CreateArrivalRequest toArrivalRequest() {
            var request = new NotificationDto.CreateArrivalRequest();
            request.setReminderOffsetMinutes(reminderOffsetMinutes);
            request.setRouteDetails(routeDetails);
            request.setScheduleType(scheduleType);
            return request;
        }
    }

    public enum EstimateStatus { ESTIMATED, UNAVAILABLE }

    @Getter
    @Builder
    public static class Response {
        private NotificationCategory category;
        private Long notificationId;
        private List<Integer> reminderOffsetMinutes;
        private String routeDetails;
        private Boolean isActive;
        private NotificationScheduleType scheduleType;
        @Schema(description = "경로 출발 지점에서 출발할 예상 적정 시각. 시간대 포함. 계산 불가 시 null.")
        private OffsetDateTime estimatedDepartureAt;
        @Schema(description = "estimatedDepartureAt - serverTime의 초 단위 값. 이미 지났으면 음수, 계산 불가 시 null.")
        private Long remainingSeconds;
        @Schema(description = "남은 시간 계산에 사용한 서버 현재 시각. 앱의 카운트다운 기준.")
        private OffsetDateTime serverTime;
        private EstimateStatus estimateStatus;
        @Schema(description = "계산 불가 사유 코드. 추정 가능 시 null.")
        private String estimateErrorCode;
    }
}
