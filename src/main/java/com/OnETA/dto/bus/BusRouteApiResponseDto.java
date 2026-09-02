package com.OnETA.dto.bus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

// 외부 API 응답 파싱용 DTO, 서울특별시_노선정보조회 서비스 (getBusRouteList) JSON 응답 매핑
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusRouteApiResponseDto {
    private MsgBody msgBody;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MsgBody {
        private List<ItemList> itemList;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ItemList {
        private String busRouteId;   // 노선 ID
        private String busRouteNm;   // 버스 번호
        private String stStationNm;  // 기점
        private String edStationNm;  // 종점
        private String term;         // 배차 간격
    }
}