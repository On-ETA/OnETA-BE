package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TransitScheduleCalculatorTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 18, 23, 0);
    private List<TransitDto.RouteSegment> route() {
        return List.of(segment("WALK", 5), segment("BUS", 10), segment("WALK", 3),
                segment("BUS", 10), segment("WALK", 2));
    }
    private TransitDto.RouteSegment segment(String type, int minutes) {
        return TransitDto.RouteSegment.builder().transitType(type).durationMinutes(minutes).build();
    }
    private SeoulBusScheduleService.LiveBus bus(int board, Integer end, boolean last) {
        return new SeoulBusScheduleService.LiveBus(now.plusMinutes(board),
                end == null ? null : now.plusMinutes(end), last, "vehicle");
    }
    @Test void choosesSecondBusWhenFirstConnectionIsMissed() {
        var plan = TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, 20, false)),
                List.of(bus(22, 32, false), bus(25, 35, false))), NotificationScheduleType.FIRST_TRANSIT, now);
        assertThat(plan.departure()).isEqualTo(now.plusMinutes(5));
        assertThat(plan.durationMinutes()).isEqualTo(32);
    }
    @Test void lastUsesEarlierUpstreamBusToCatchDownstreamLast() {
        var plan = TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, 20, false), bus(20, 30, true)),
                List.of(bus(25, 35, true))), NotificationScheduleType.LAST_TRANSIT, now);
        assertThat(plan.departure()).isEqualTo(now.plusMinutes(5));
    }
    @Test void noLastMarkerDoesNotTurnOrdinaryTrafficIntoLastService() {
        assertThat(TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, 20, false)),
                List.of(bus(25, 35, false))), NotificationScheduleType.LAST_TRANSIT, now)).isNull();
    }
    @Test void disconnectedOrIncompletePredictionsDoNotProduceNotification() {
        assertThat(TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, 30, false)),
                List.of(bus(25, 35, true))), NotificationScheduleType.LAST_TRANSIT, now)).isNull();
        assertThat(TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, null, false)),
                List.of(bus(25, 35, true))), NotificationScheduleType.LAST_TRANSIT, now)).isNull();
        assertThat(TransitScheduleCalculator.live(route(), List.of(List.of(bus(10, 20, false)),
                List.of()), NotificationScheduleType.FIRST_TRANSIT, now)).isNull();
    }
    @Test void accessWalkMustStillBeReachableAndDateRolloverIsPreserved() {
        assertThat(TransitScheduleCalculator.live(route(), List.of(List.of(bus(3, 13, false)),
                List.of(bus(25, 35, true))), NotificationScheduleType.LAST_TRANSIT, now)).isNull();
        var plan = TransitScheduleCalculator.live(route(), List.of(List.of(bus(55, 65, false)),
                List.of(bus(70, 80, true))), NotificationScheduleType.LAST_TRANSIT, now);
        assertThat(plan.departure()).isEqualTo(now.plusMinutes(50));
        assertThat(plan.durationMinutes()).isEqualTo(32);
    }
}
