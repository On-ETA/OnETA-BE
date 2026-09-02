package com.OnETA.dto.bus;

import com.OnETA.entity.BusDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
    public class DepotNotificationRequestDto {

    @NotBlank(message = "노선 ID는 필수 입력값입니다.")
    private String routeId;

    @NotBlank(message = "버스 번호는 필수 입력값입니다.")
    private String busNumber;

    @NotNull(message = "방면을 선택해주세요.")
    private BusDirection direction;

    @NotBlank(message = "방면 이름은 필수 입력값입니다. (예: 강남역 방면)")
    private String directionName;
}
