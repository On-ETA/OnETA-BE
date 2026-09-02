package com.OnETA.dto.bus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

// 외부 API 응답 파싱용 DTO, 서울특별시_버스위치정보조회 서비스 (getBusPosByRtid) JSON 응답 매핑
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusPosApiResponseDto {
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
        private String plainNo;   // 차량번호 (예: 서울74사1234)
        private String sectOrd;   // 구간 순번 (정류장 순서 - 이 값으로 출발 여부 판별)
        private String stopFlag;  // 정류소 도착여부 (0:운행중, 1:도착)
        private String dataTm;    // 데이터 제공 시간
        private String vehId;     // 버스 고유 ID
    }
}