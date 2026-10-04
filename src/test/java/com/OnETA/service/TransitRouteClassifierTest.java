package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitRouteClassifierTest {

    @Test
    void recognizesSeoulNightBusNames() {
        assertThat(TransitRouteClassifier.isNightBusName("N62")).isTrue();
        assertThat(TransitRouteClassifier.isNightBusName(" n13 ")).isTrue();
        assertThat(TransitRouteClassifier.isNightBusName("273")).isFalse();
        assertThat(TransitRouteClassifier.isNightBusName("M7731")).isFalse();
    }

    @Test
    void nightOnlyIgnoresWalkingButRequiresEveryRideToBeNightBus() {
        var walk = TransitDto.RouteSegment.builder()
                .transitType("WALK")
                .durationMinutes(5)
                .build();
        var nightBus = TransitDto.RouteSegment.builder()
                .transitType("BUS")
                .transitName("N62")
                .nightBus(true)
                .durationMinutes(30)
                .build();
        var subway = TransitDto.RouteSegment.builder()
                .transitType("SUBWAY")
                .transitName("2호선")
                .durationMinutes(15)
                .build();

        var nightOnly = TransitDto.RouteOptionResponse.builder()
                .segments(List.of(walk, nightBus, walk))
                .build();
        var transfer = TransitDto.RouteOptionResponse.builder()
                .segments(List.of(walk, nightBus, subway, walk))
                .build();

        assertThat(TransitRouteClassifier.isNightOnlyRoute(nightOnly)).isTrue();
        assertThat(TransitRouteClassifier.isNightOnlyRoute(transfer)).isFalse();
    }
}
