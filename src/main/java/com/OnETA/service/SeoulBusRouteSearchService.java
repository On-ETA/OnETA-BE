package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.bus.BusDirectionResponseDto;
import com.OnETA.dto.bus.BusRouteSearchResponseDto;
import com.OnETA.entity.SeoulBusRoute;
import com.OnETA.repository.SeoulBusRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 읽기 전용으로 설정
public class SeoulBusRouteSearchService {

    private final SeoulBusRouteRepository seoulBusRouteRepository;

    public List<BusRouteSearchResponseDto> searchBusRoutes(String keyword) {
        // DB에서 키워드가 포함된 모든 노선 조회 (예: "72" 검색 시 720, 720-1, N72 등 모두 포함)
        List<SeoulBusRoute> rawRoutes = seoulBusRouteRepository.findByRouteNmContaining(keyword);

        // 사용자 경험을 위한 정렬 및 DTO 변환
        return rawRoutes.stream()
                .sorted((a, b) -> {
                    boolean aExactMatch = a.getRouteNm().equals(keyword);
                    boolean bExactMatch = b.getRouteNm().equals(keyword);

                    // 검색어와 정확히 일치하는 번호를 무조건 리스트 최상단으로 올림
                    if (aExactMatch && !bExactMatch) return -1;
                    if (!aExactMatch && bExactMatch) return 1;

                    // 둘 다 정확히 일치하지 않거나, 둘 다 일치하면 이름 오름차순 정렬
                    return a.getRouteNm().compareTo(b.getRouteNm());
                })
                .map(BusRouteSearchResponseDto::from)
                .collect(Collectors.toList());
    }


    public BusDirectionResponseDto getBusDirections(String routeId) {
        SeoulBusRoute route = seoulBusRouteRepository.findById(routeId)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "해당 노선을 찾을 수 없습니다."));

        return BusDirectionResponseDto.from(route);
    }




}