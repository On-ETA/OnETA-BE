package com.OnETA.dto.bus;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

//
@Getter
@Builder
public class BusLocationResponseDto {

    private String routeId;

    // 차고지 출발 여부 및 차량 번호
    private boolean depotDeparted;
    private String depotDepartedBusNo;

    // 회차지 출발 여부 및 차량 번호
    private boolean turnaroundDeparted;
    private String turnaroundDepartedBusNo;

    private List<BusPosition> activeBuses;

    @Getter
    @Builder
    public static class BusPosition {
        private String plainNo;
        private int sectOrd;
        private boolean atStop;

        // 차고지 출발 조건: 정류장 순번이 1 또는 2
        public boolean isDepotDeparted() {
            return sectOrd == 1 || sectOrd == 2;
        }

        // 회차지 출발 조건: DB에 저장된 회차지 순번과 일치하거나 그 다음 정류장일 때
        public boolean isTurnaroundDeparted(Integer turnaroundSeq) {
            if (turnaroundSeq == null) return false;
            return sectOrd == turnaroundSeq || sectOrd == turnaroundSeq + 1;
        }
    }
}