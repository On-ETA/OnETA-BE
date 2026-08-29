package com.OnETA.dto.bus;

import com.OnETA.entity.BusDirection;
import com.OnETA.entity.DepotNotification;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DepotNotificationResponseDto {
    private Long userBusId;  // 프론트엔드에서 삭제 API 호출 시 사용할 수 있는 ID
    private String routeId;
    private String busNumber;
    private BusDirection direction;
    private String directionName;
    private boolean active;

    public static DepotNotificationResponseDto from(DepotNotification notification) {
        return DepotNotificationResponseDto.builder()
                .userBusId(notification.getUserBus().getId())
                .routeId(notification.getUserBus().getRouteId())
                .busNumber(notification.getUserBus().getBusNumber())
                .direction(notification.getUserBus().getDirection())
                .directionName(notification.getUserBus().getDirectionName())
                .active(notification.isActive())
                .build();
    }
}