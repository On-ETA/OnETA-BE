package com.OnETA.dto.bus;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DepotNotificationStatusRequestDto {

    @NotNull(message = "활성화 여부(active)를 지정해주세요.")
    private Boolean active;
}