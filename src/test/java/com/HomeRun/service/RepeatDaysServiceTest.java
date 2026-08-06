package com.HomeRun.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatDaysServiceTest {
    private final RepeatDaysService service = new RepeatDaysService();

    @Test
    void convertsWeekdaysToBitmaskAndBack() {
        int mask = service.toMask(List.of("MON", "WED", "FRI"));

        assertThat(mask).isEqualTo((1 << 0) | (1 << 2) | (1 << 4));
        assertThat(service.toDays(mask)).containsExactly("MON", "WED", "FRI");
        assertThat(service.includes(mask, DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(service.includes(mask, DayOfWeek.TUESDAY)).isFalse();
    }

    @Test
    void emptyWeekdayListMeansOneTimeNotification() {
        assertThat(service.toMask(List.of())).isZero();
        assertThat(service.toDays(0)).isEmpty();
    }
}
