package com.OnETA.dto.bus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

// 외부 API 응답 파싱용 DTO, 서울특별시_노선정보조회 서비스 (getStaionByRoute) JSON 응답 매핑
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusRouteStationApiResponseDto {
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
        private String seq;      // 정류장 순번
        private String transYn;  // 회차지 여부 (Y: 회차지, N: 일반)
    }
}