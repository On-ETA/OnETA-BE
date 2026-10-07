package com.OnETA.service;

import java.time.LocalDateTime;

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

    boolean contains(LocalDateTime time) {
        return !time.isBefore(first) && time.isBefore(last);
    }
}
