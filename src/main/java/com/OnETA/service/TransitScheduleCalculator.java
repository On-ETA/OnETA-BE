package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Connects known first/last departures; never invents an intermediate bus departure. */
final class TransitScheduleCalculator {
    private TransitScheduleCalculator() { }

    record Plan(LocalDateTime departure, int durationMinutes) { }

    /** Conservative fallback is an estimate, not proof of a catchable connection. */
    static Plan conservative(List<TransitDto.RouteSegment> segments, List<LocalDateTime> boundaries,
                             NotificationScheduleType type) {
        if (boundaries == null || boundaries.isEmpty() || boundaries.stream().anyMatch(Objects::isNull)) throw unsupported();
        int prefix = 0, ride = 0;
        LocalDateTime departure = null;
        for (var segment : segments) {
            if (segment.getDurationMinutes() == null || segment.getDurationMinutes() < 0) throw unsupported();
            if (!"WALK".equals(segment.getTransitType())) {
                if (ride >= boundaries.size()) throw unsupported();
                LocalDateTime candidate = boundaries.get(ride).minusMinutes(prefix + 5L);
                if (departure == null || (type == NotificationScheduleType.LAST_TRANSIT && candidate.isBefore(departure)))
                    departure = candidate;
                ride++;
                prefix += Math.max(3, (int) Math.ceil(segment.getDurationMinutes() * 0.25));
                if (ride < boundaries.size()) prefix += 10; // extra transfer/wait allowance
            }
            prefix += segment.getDurationMinutes();
        }
        if (ride != boundaries.size() || departure == null) throw unsupported();
        return new Plan(departure, prefix + 5);
    }

    static Plan live(List<TransitDto.RouteSegment> segments,
                     List<List<SeoulBusScheduleService.LiveBus>> choices,
                     NotificationScheduleType type, LocalDateTime now) {
        if (choices.isEmpty() || choices.size() > 5 || choices.stream().anyMatch(List::isEmpty)) return null;
        List<Plan> plans = new java.util.ArrayList<>();
        connect(segments, choices, type, now, 0, 0, now, null, false, plans);
        var order = java.util.Comparator.comparing(Plan::departure);
        return (type == NotificationScheduleType.FIRST_TRANSIT ? plans.stream().min(order)
                : plans.stream().max(order)).orElse(null);
    }

    private static void connect(List<TransitDto.RouteSegment> segments,
                                List<List<SeoulBusScheduleService.LiveBus>> choices,
                                NotificationScheduleType type, LocalDateTime now,
                                int index, int ride, LocalDateTime ready, LocalDateTime departure,
                                boolean lastSeen, List<Plan> plans) {
        if (index == segments.size()) {
            if (departure == null || !departure.isAfter(now)
                    || (type == NotificationScheduleType.LAST_TRANSIT && !lastSeen)) return;
            long minutes = (Duration.between(departure, ready).getSeconds() + 59) / 60;
            if (minutes > 0 && minutes <= Integer.MAX_VALUE) plans.add(new Plan(departure, (int) minutes));
            return;
        }
        var segment = segments.get(index);
        if ("WALK".equals(segment.getTransitType())) {
            connect(segments, choices, type, now, index + 1, ride,
                    ready.plusMinutes(segment.getDurationMinutes()), departure, lastSeen, plans);
            return;
        }
        for (var bus : choices.get(ride)) {
            // Require the same vehicle's downstream prediction: static search times
            // alone cannot validate a transfer when traffic conditions change.
            if (bus.alighting() == null || !bus.alighting().isAfter(bus.boarding())
                    || bus.boarding().isBefore(ready)) continue;
            LocalDateTime start = departure == null
                    ? bus.boarding().minus(Duration.between(now, ready)) : departure;
            connect(segments, choices, type, now, index + 1, ride + 1, bus.alighting(),
                    start, lastSeen || bus.last(), plans);
        }
    }

    static Plan calculate(List<TransitDto.RouteSegment> segments, List<LocalDateTime> times,
                          NotificationScheduleType type) {
        if (segments == null || segments.isEmpty() || times == null || times.isEmpty()
                || times.stream().anyMatch(t -> t == null)
                || (type != NotificationScheduleType.FIRST_TRANSIT && type != NotificationScheduleType.LAST_TRANSIT)) {
            throw unsupported();
        }
        int rides = 0;
        for (var s : segments) {
            if (s == null || s.getDurationMinutes() == null || s.getDurationMinutes() < 0
                    || s.getTransitType() == null
                    || !List.of("WALK", "BUS", "SUBWAY").contains(s.getTransitType())) throw unsupported();
            if (!"WALK".equals(s.getTransitType())) rides++;
        }
        if (rides != times.size()) throw unsupported();

        // The first vehicle's departure fixes access time. Later vehicles must be
        // reachable at their known boundary departure, rather than a fabricated time.
        LocalDateTime departure = null;
        LocalDateTime ready = null;
        int access = 0;
        int ride = 0;
        for (var s : segments) {
            if ("WALK".equals(s.getTransitType())) {
                if (ready == null) access = Math.addExact(access, s.getDurationMinutes());
                else ready = ready.plusMinutes(s.getDurationMinutes());
            } else {
                LocalDateTime boarding = times.get(ride++);
                if (ready == null) departure = boarding.minusMinutes(access);
                else if (ready.isAfter(boarding)) {
                    // FIRST would need a later service; LAST would need an earlier
                    // upstream service. Neither is known from boundary times alone.
                    throw new GlobalException(ErrorCode.TRANSIT_CONNECTION_UNVERIFIED);
                }
                ready = boarding.plusMinutes(s.getDurationMinutes());
            }
        }
        long duration = Duration.between(departure, ready).toMinutes();
        if (duration <= 0 || duration > Integer.MAX_VALUE) throw unsupported();
        return new Plan(departure, (int) duration);
    }

    private static GlobalException unsupported() {
        return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
    }
}
