package com.OnETA.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;
import java.util.Set;

/**
 * Shared subway service-day selection for ODsay, TAGO and Seoul Metro.
 *
 * Public-holiday dates: Korea Astronomy and Space Science Institute calendar
 * (https://astro.kasi.re.kr/kor/life/post/calendarData?search_year=2026)
 * and (https://astro.kasi.re.kr/kor/life/post/calendarData?search_year=2027).
 *
 * Movable lunar holidays, substitute holidays and election days must be updated
 * from the published calendar before each additional operating year.
 */
final class KoreanSubwayServiceDay {
    enum Type { WEEKDAY, SATURDAY, HOLIDAY }

    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(3, 1), MonthDay.of(5, 5),
            MonthDay.of(6, 6), MonthDay.of(8, 15), MonthDay.of(10, 3),
            MonthDay.of(10, 9), MonthDay.of(12, 25));

    // Labor Day and Constitution Day became public holidays in 2026.
    private static final Set<MonthDay> NEW_2026_HOLIDAYS = Set.of(
            MonthDay.of(5, 1), MonthDay.of(7, 17));

    private static final Map<Integer, Set<LocalDate>> YEARLY_HOLIDAYS = Map.of(
            2026, dates(
                    "2026-02-16", "2026-02-17", "2026-02-18",
                    "2026-03-02", "2026-05-24", "2026-05-25",
                    "2026-06-03", "2026-08-17",
                    "2026-09-24", "2026-09-25", "2026-09-26",
                    "2026-10-05"),
            2027, dates(
                    "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09",
                    "2027-05-03", "2027-05-13", "2027-07-19",
                    "2027-08-16", "2027-09-14", "2027-09-15",
                    "2027-09-16", "2027-10-04", "2027-10-11",
                    "2027-12-27"));

    private KoreanSubwayServiceDay() { }

    static Type of(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("serviceDate is required");
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY || isPublicHoliday(date)) return Type.HOLIDAY;
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) return Type.SATURDAY;
        return Type.WEEKDAY;
    }

    static String odsayDay(LocalDate date) {
        return switch (of(date)) {
            case WEEKDAY -> "1";
            case SATURDAY -> "2";
            case HOLIDAY -> "3";
        };
    }

    static String tagoDay(LocalDate date) {
        return switch (of(date)) {
            case WEEKDAY -> "01";
            case SATURDAY -> "02";
            case HOLIDAY -> "03";
        };
    }

    static String seoulMetroDay(LocalDate date) {
        return of(date) == Type.WEEKDAY ? "평일" : "주말";
    }

    private static boolean isPublicHoliday(LocalDate date) {
        MonthDay monthDay = MonthDay.from(date);
        return FIXED_HOLIDAYS.contains(monthDay)
                || (date.getYear() >= 2026 && NEW_2026_HOLIDAYS.contains(monthDay))
                || YEARLY_HOLIDAYS.getOrDefault(date.getYear(), Set.of()).contains(date);
    }

    private static Set<LocalDate> dates(String... values) {
        java.util.HashSet<LocalDate> result = new java.util.HashSet<>();
        for (String value : values) result.add(LocalDate.parse(value));
        return Set.copyOf(result);
    }
}
