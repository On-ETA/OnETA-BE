package com.OnETA.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** A recurring bus operating interval, anchored to the current clock rather than midnight. */
record TransitOperatingWindow(LocalDateTime first, LocalDateTime last) {
    TransitOperatingWindow {
        // Some subway providers also use 00:xx instead of 24:xx.
        if (last.toLocalDate().equals(first.toLocalDate()) && last.isBefore(first)) {
            last = last.plusDays(1);
        }
    }
    TransitOperatingWindow at(LocalDateTime now) {
        // Date-less/current Seoul bus information describes a daily interval, not a
        // historical timetable. Only select its preceding occurrence while it is active.
        var preceding = new TransitOperatingWindow(first.minusDays(1), last.minusDays(1));
        return preceding.contains(now) ? preceding : this;
    }

    /**
     * Seoul's station API sometimes reports an N-bus stop's 00:xx–02:xx
     * timetable with today's absolute date, even when queried at 23:xx.
     * Only repeat that early-morning interval into the immediately upcoming
     * morning during the SAME night (21:00–06:00), never tomorrow evening.
     */
    TransitOperatingWindow alignUpcomingNightBus(LocalDateTime now) {
        if (!now.toLocalTime().isBefore(LocalTime.of(21, 0))
                && first.toLocalDate().equals(now.toLocalDate())
                && last.toLocalDate().equals(now.toLocalDate())
                && first.toLocalTime().isBefore(LocalTime.of(6, 0))
                && last.toLocalTime().isBefore(LocalTime.of(6, 0))) {
            return new TransitOperatingWindow(first.plusDays(1), last.plusDays(1));
        }
        return this;
    }

    /**
     * An N bus can start after the current query (e.g. 00:45 after 23:52).
     * Only allow future starts inside the current overnight search window.
     */
    boolean catchableThisNight(LocalDateTime now) {
        if (contains(now)) return true;
        if (!now.isBefore(first) || now.toLocalTime().isAfter(LocalTime.of(6, 0))
                && now.toLocalTime().isBefore(LocalTime.of(21, 0))) return false;
        LocalDateTime cutoff = now.toLocalTime().isBefore(LocalTime.of(6, 0))
                ? now.toLocalDate().atTime(6, 0)
                : now.toLocalDate().plusDays(1).atTime(6, 0);
        return !first.isAfter(cutoff) && last.isAfter(now);
    }

    boolean contains(LocalDateTime time) {
        return !time.isBefore(first) && time.isBefore(last);
    }
}
