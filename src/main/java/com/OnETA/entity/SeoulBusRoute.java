package com.OnETA.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seoul_bus_route")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SeoulBusRoute {

    @Id
    private String routeId;      // 노선 ID (busRouteId)

    private String routeNm;         // 노선 번호 (busRouteNm)
    private String startPoint;      // 기점 (stStationNm)
    private String endPoint;        // 종점 (edStationNm)
    private String term;            // 배차 간격 (term)
    private Integer turnaroundSeq;  // 회차지 정류장 순번 (seq)
}
