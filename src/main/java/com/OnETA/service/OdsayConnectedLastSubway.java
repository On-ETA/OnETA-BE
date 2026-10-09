package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses ODsay's connected LAST subway itinerary, not independent terminal trains.
 * Only accepts an itinerary that follows the exact subway legs selected by the user.
 */
final class OdsayConnectedLastSubway {
    private static final Pattern LINE = Pattern.compile("([1-9])호선");
    private static final int ACCESS_BUFFER_MINUTES = 5;

    private OdsayConnectedLastSubway() { }

    static boolean supports(TransitDto.RouteOptionResponse route) {
        if (route == null || !"ODSAY".equals(route.getProvider())
                || route.getSegments() == null) return false;
        List<TransitDto.RouteSegment> rides = rides(route);
        if (rides.size() < 2) return false;
        for (TransitDto.RouteSegment ride : rides) {
            if (!"SUBWAY".equals(ride.getTransitType())
                    || blank(ride.getOdsayStartStationId()) || blank(ride.getOdsayEndStationId())
                    || line(ride.getTransitName()) == null) return false;
        }
        return true;
    }

    static Optional<LocalDateTime> find(
            TransitDto.RouteOptionResponse route, JsonNode response, LocalDate serviceDay) {
        if (!supports(route) || response == null || serviceDay == null) return Optional.empty();
        JsonNode paths = response.path("result").path("path");
        if (!paths.isArray()) return Optional.empty();

        List<TransitDto.RouteSegment> rides = rides(route);
        int access = accessMinutes(route);
        LocalDateTime best = null;
        for (JsonNode path : paths) {
            List<JsonNode> legs = new ArrayList<>();
            JsonNode subPaths = path.path("subPath");
            if (!subPaths.isArray()) continue;
            for (JsonNode leg : subPaths) {
                if (leg.path("movingType").asInt(-1) == 1) legs.add(leg);
            }
            if (legs.size() != rides.size() || !sameLegs(rides, legs)) continue;

            LocalDateTime firstBoarding = null, previousArrival = null;
            boolean valid = true;
            for (int i = 0; i < legs.size(); i++) {
                JsonNode leg = legs.get(i);
                LocalDateTime boarding = time(leg.path("departureTime").asText(null), serviceDay);
                LocalDateTime alighting = time(leg.path("arrivalTime").asText(null), serviceDay);
                if (boarding == null || alighting == null) { valid = false; break; }
                if (i == 0 && boarding.getHour() < 6 && boarding.toLocalDate().equals(serviceDay)) {
                    boarding = boarding.plusDays(1);
                }
                if (previousArrival != null && boarding.isBefore(previousArrival)
                        && boarding.getHour() < 6 && boarding.toLocalDate().equals(serviceDay)) {
                    boarding = boarding.plusDays(1);
                }
                if (previousArrival != null) {
                    int requiredWalk = transferWalkMinutes(route, i);
                    if (boarding.isBefore(previousArrival.plusMinutes(requiredWalk))) {
                        valid = false;
                        break;
                    }
                }
                while (!alighting.isAfter(boarding) && alighting.isBefore(boarding.plusDays(1))) {
                    alighting = alighting.plusDays(1);
                }
                long rideMinutes = Duration.between(boarding, alighting).toMinutes();
                if (rideMinutes <= 0 || rideMinutes > 180) { valid = false; break; }
                if (firstBoarding == null) firstBoarding = boarding;
                previousArrival = alighting;
            }
            if (!valid || firstBoarding == null) continue;
            LocalDateTime departure = firstBoarding.minusMinutes(access + ACCESS_BUFFER_MINUTES);
            if (best == null || departure.isAfter(best)) best = departure;
        }
        return Optional.ofNullable(best);
    }

    static String originId(TransitDto.RouteOptionResponse route) {
        return rides(route).get(0).getOdsayStartStationId();
    }

    static String destinationId(TransitDto.RouteOptionResponse route) {
        List<TransitDto.RouteSegment> rides = rides(route);
        return rides.get(rides.size() - 1).getOdsayEndStationId();
    }

    /**
     * ODsay can otherwise pick a different subway interchange between SID and
     * EID. Force the selected transfer stop when exactly two lines are involved.
     * Keep strict leg verification even after supplying MID.
     */
    static Optional<String> transferId(TransitDto.RouteOptionResponse route) {
        if (!supports(route)) return Optional.empty();
        List<TransitDto.RouteSegment> transfers = rides(route);
        if (transfers.size() != 2) return Optional.empty();
        var before = transfers.get(0);
        var after = transfers.get(1);
        if (!sameStationName(before.getEndStation(), after.getStartStation())) {
            return Optional.empty();
        }
        return Optional.of(before.getOdsayEndStationId());
    }

    private static boolean sameStationName(String before, String after) {
        if (blank(before) || blank(after)) return false;
        String left = before.trim().replaceAll("역$", "");
        String right = after.trim().replaceAll("역$", "");
        return left.equals(right);
    }

    /** Non-sensitive evidence about why a connected LAST answer was rejected. */
    static String mismatchReason(TransitDto.RouteOptionResponse route, JsonNode response) {
        if (!supports(route)) return "NOT_ELIGIBLE";
        JsonNode paths = response == null ? null : response.path("result").path("path");
        if (paths == null || !paths.isArray()) return "NO_PATH_ARRAY";
        if (paths.isEmpty()) return "EMPTY_PATHS";
        List<TransitDto.RouteSegment> expected = rides(route);
        boolean candidateWithExpectedLegs = false;
        boolean exactLegs = false;
        for (JsonNode path : paths) {
            var subPaths = path.path("subPath");
            if (!subPaths.isArray()) continue;
            List<JsonNode> legs = new ArrayList<>();
            for (JsonNode leg : subPaths) {
                if (leg.path("movingType").asInt(-1) == 1) legs.add(leg);
            }
            if (legs.size() != expected.size()) continue;
            candidateWithExpectedLegs = true;
            if (sameLegs(expected, legs)) exactLegs = true;
        }
        if (!candidateWithExpectedLegs) return "LEG_COUNT_OR_STRUCTURE_MISMATCH";
        return exactLegs ? "TIMING_OR_TRANSFER_MISMATCH" : "LINE_OR_STATION_MISMATCH";
    }

    private static boolean sameLegs(List<TransitDto.RouteSegment> rides, List<JsonNode> legs) {
        for (int i = 0; i < rides.size(); i++) {
            TransitDto.RouteSegment expected = rides.get(i);
            JsonNode actual = legs.get(i);
            if (!expected.getOdsayStartStationId().equals(actual.path("startID").asText())
                    || !expected.getOdsayEndStationId().equals(actual.path("endID").asText())
                    || !line(expected.getTransitName()).equals(line(actual.path("laneName").asText("")))) {
                return false;
            }
        }
        return true;
    }

    private static String line(String name) {
        if (name == null) return null;
        Matcher m = LINE.matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<TransitDto.RouteSegment> rides(TransitDto.RouteOptionResponse route) {
        if (route == null || route.getSegments() == null) return List.of();
        return route.getSegments().stream().filter(s -> s != null && !"WALK".equals(s.getTransitType())).toList();
    }

    private static int accessMinutes(TransitDto.RouteOptionResponse route) {
        int minutes = 0;
        for (TransitDto.RouteSegment segment : route.getSegments()) {
            if (!"WALK".equals(segment.getTransitType())) break;
            if (segment.getDurationMinutes() != null) minutes += Math.max(0, segment.getDurationMinutes());
        }
        return minutes;
    }

    /** Walking segments strictly between the previous and current subway legs. */
    private static int transferWalkMinutes(TransitDto.RouteOptionResponse route, int rideIndex) {
        int rides = 0, walk = 0;
        for (TransitDto.RouteSegment segment : route.getSegments()) {
            if (!"WALK".equals(segment.getTransitType())) {
                if (rides == rideIndex) return walk;
                rides++;
                walk = 0;
            } else if (rides > 0 && segment.getDurationMinutes() != null) {
                walk += Math.max(0, segment.getDurationMinutes());
            }
        }
        return walk;
    }

    private static LocalDateTime time(String value, LocalDate day) {
        if (value == null || day == null) return null;
        try {
            String digits = value.replace(":", "").trim();
            if (digits.length() < 4) return null;
            int hour = Integer.parseInt(digits.substring(0, 2));
            int minute = Integer.parseInt(digits.substring(2, 4));
            if (hour < 0 || hour >= 48 || minute < 0 || minute >= 60) return null;
            return LocalDateTime.of(day.plusDays(hour / 24), LocalTime.of(hour % 24, minute));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
