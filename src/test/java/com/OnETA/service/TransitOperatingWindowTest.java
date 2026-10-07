package com.OnETA.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class TransitOperatingWindowTest {
    private final LocalDate day = LocalDate.of(2026, 10, 7);

    @Test
    void ordinaryOvernightBusAndNightBusSelectTheirActiveInterval() {
        var ordinary = new TransitOperatingWindow(day.atTime(4, 30), day.plusDays(1).atTime(0, 20));
        var night = new TransitOperatingWindow(day.atTime(23, 30), day.plusDays(1).atTime(3, 10));
        assertThat(ordinary.at(day.atTime(0, 2)).last()).isEqualTo(day.atTime(0, 20));
        assertThat(night.at(day.atTime(0, 2)).last()).isEqualTo(day.atTime(3, 10));
    }

    @Test
    void endedBusDoesNotBecomeActiveJustBecauseTonightsLastIsInFuture() {
        var window = new TransitOperatingWindow(day.atTime(5, 30), day.atTime(23, 6));
        assertThat(window.at(day.atTime(0, 2)).contains(day.atTime(0, 2))).isFalse();
    }

    @Test
    void exactLastIsExpiredAndNightBusDoesNotHaveAFourAmCutoff() {
        var window = new TransitOperatingWindow(day.atTime(23, 30), day.plusDays(1).atTime(5, 10));
        assertThat(window.at(day.atTime(4, 30)).contains(day.atTime(4, 30))).isTrue();
        assertThat(window.at(day.atTime(5, 10)).contains(day.atTime(5, 10))).isFalse();
    }
}
