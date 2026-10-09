package com.OnETA.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanSubwayServiceDayTest {
    @Test
    void hangulDayFridayUsesHolidayTimetableInAllProviders() {
        var day = LocalDate.of(2026, 10, 9);
        assertThat(KoreanSubwayServiceDay.of(day)).isEqualTo(KoreanSubwayServiceDay.Type.HOLIDAY);
        assertThat(KoreanSubwayServiceDay.odsayDay(day)).isEqualTo("3");
        assertThat(KoreanSubwayServiceDay.tagoDay(day)).isEqualTo("03");
        assertThat(KoreanSubwayServiceDay.seoulMetroDay(day)).isEqualTo("주말");
    }

    @Test
    void distinguishesRegularFridayAndSaturdayWithoutMixingTimetables() {
        assertThat(KoreanSubwayServiceDay.odsayDay(LocalDate.of(2026, 10, 8))).isEqualTo("1");
        assertThat(KoreanSubwayServiceDay.tagoDay(LocalDate.of(2026, 10, 8))).isEqualTo("01");
        assertThat(KoreanSubwayServiceDay.seoulMetroDay(LocalDate.of(2026, 10, 8))).isEqualTo("평일");
        assertThat(KoreanSubwayServiceDay.odsayDay(LocalDate.of(2026, 10, 10))).isEqualTo("2");
        assertThat(KoreanSubwayServiceDay.odsayDay(LocalDate.of(2026, 10, 11))).isEqualTo("3");
    }

    @Test
    void includesSubstituteHolidaysLunarHolidaysAndElectionDay2026() {
        for (String date : new String[] {
                "2026-02-16", "2026-02-17", "2026-02-18",
                "2026-03-02", "2026-05-01", "2026-05-25",
                "2026-06-03", "2026-07-17", "2026-08-17",
                "2026-09-24", "2026-09-25", "2026-10-05" }) {
            assertThat(KoreanSubwayServiceDay.of(LocalDate.parse(date)))
                    .as("2026 holiday %s", date)
                    .isEqualTo(KoreanSubwayServiceDay.Type.HOLIDAY);
        }
    }

    @Test
    void includesKnown2027MovableAndSubstituteHolidays() {
        for (String date : new String[] {
                "2027-02-09", "2027-05-03", "2027-05-13",
                "2027-07-19", "2027-08-16", "2027-09-15",
                "2027-10-04", "2027-10-11", "2027-12-27" }) {
            assertThat(KoreanSubwayServiceDay.of(LocalDate.parse(date)))
                    .as("2027 holiday %s", date)
                    .isEqualTo(KoreanSubwayServiceDay.Type.HOLIDAY);
        }
    }

    @Test
    void pre2026LaborAndConstitutionDaysRemainWeekdaysWhenApplicable() {
        assertThat(KoreanSubwayServiceDay.of(LocalDate.of(2025, 5, 1)))
                .isEqualTo(KoreanSubwayServiceDay.Type.WEEKDAY);
        assertThat(KoreanSubwayServiceDay.of(LocalDate.of(2025, 7, 17)))
                .isEqualTo(KoreanSubwayServiceDay.Type.WEEKDAY);
    }
}
