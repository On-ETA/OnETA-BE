package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitNotificationDto;
import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationCategory;
import com.OnETA.entity.NotificationScheduleType;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransitNotificationQueryService {
    private final ArrivalNotificationRepository notifications;
    private final UserRepository users;
    private final TransitScheduleService schedules;
    @Value("${app.time-zone:Asia/Seoul}")
    private String timeZone = "Asia/Seoul";
    private Clock clock = Clock.systemUTC();

    public List<TransitNotificationDto.Response> getCurrentNotifications(String email) {
        var user = users.findByEmail(email).orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneId.of(timeZone));
        var activeTransit = notifications.findAllByUserId(user.getId()).stream()
                .filter(this::isTransit)
                .filter(n -> !n.isTransitArchived())
                .filter(n -> Boolean.TRUE.equals(n.getIsActive()))
                .toList();

        return List.of(NotificationScheduleType.FIRST_TRANSIT, NotificationScheduleType.LAST_TRANSIT).stream()
                .map(type -> activeTransit.stream()
                        .filter(n -> n.getScheduleType() == type)
                        .max(Comparator.comparing(ArrivalNotification::getId))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(n -> response(n, now))
                .toList();
    }

    public List<TransitNotificationDto.Response> getCurrentNotifications(
            String email, NotificationScheduleType scheduleType) {
        if (scheduleType != NotificationScheduleType.FIRST_TRANSIT
                && scheduleType != NotificationScheduleType.LAST_TRANSIT) {
            throw new GlobalException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "FIRST_TRANSIT 또는 LAST_TRANSIT을 지정해주세요.");
        }

        var user = users.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneId.of(timeZone));

        return notifications.findAllByUserId(user.getId()).stream()
                .filter(this::isTransit)
                .filter(n -> !n.isTransitArchived())
                .filter(n -> Boolean.TRUE.equals(n.getIsActive()))
                .filter(n -> n.getScheduleType() == scheduleType)
                .max(Comparator.comparing(ArrivalNotification::getId))
                .map(n -> List.of(response(n, now)))
                .orElseGet(List::of);
    }

    public TransitNotificationDto.Response getCurrentNotification(String email) {
        return getCurrentNotifications(email).stream()
                .max(Comparator.comparing(TransitNotificationDto.Response::getNotificationId))
                .orElse(null);
    }

    public TransitNotificationDto.Response getNotification(String email, Long id) {
        var user = users.findByEmail(email).orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
        var notification = notifications.findById(id)
                .orElseThrow(() -> new GlobalException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.getUser().getId().equals(user.getId())) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED);
        }
        if (!isTransit(notification) || notification.isTransitArchived()
                || !Boolean.TRUE.equals(notification.getIsActive())) {
            throw new GlobalException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return response(notification, OffsetDateTime.ofInstant(clock.instant(), ZoneId.of(timeZone)));
    }

    private boolean isTransit(ArrivalNotification notification) {
        return notification.getScheduleType() == NotificationScheduleType.FIRST_TRANSIT
                || notification.getScheduleType() == NotificationScheduleType.LAST_TRANSIT;
    }

    private TransitNotificationDto.Response response(ArrivalNotification notification, OffsetDateTime now) {
        var result = TransitNotificationDto.Response.builder()
                .category(NotificationCategory.TRANSIT)
                .notificationId(notification.getId())
                .reminderOffsetMinutes(notification.getReminderOffsetMinutesList())
                .routeDetails(notification.getRouteDetails()).isActive(notification.getIsActive())
                .scheduleType(notification.getScheduleType()).serverTime(now);
        try {
            var departure = schedules.estimateDeparture(notification, now.toLocalDateTime(), ZoneId.of(timeZone))
                    .atZone(ZoneId.of(timeZone)).toOffsetDateTime();
            if (!departure.toInstant().isAfter(now.toInstant())) {
                return result.estimateStatus(TransitNotificationDto.EstimateStatus.UNAVAILABLE)
                        .estimateErrorCode(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE.getCode()).build();
            }
            return result.estimatedDepartureAt(departure)
                    .remainingSeconds(Duration.between(now.toInstant(), departure.toInstant()).getSeconds())
                    .estimateStatus(TransitNotificationDto.EstimateStatus.ESTIMATED).build();
        } catch (GlobalException e) {
            if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                log.error("Transit estimate failed (T006): notificationId={}, scheduleType={}, exceptionClass={}, message={}",
                        notification.getId(), notification.getScheduleType(), e.getClass().getName(), e.getMessage(), e);
            }
            // Return the saved setting even when a departure estimate is unavailable.
            return result.estimateStatus(TransitNotificationDto.EstimateStatus.UNAVAILABLE)
                    .estimateErrorCode(e.getErrorCode().getCode()).build();
        } catch (IllegalStateException e) {
            log.error("Transit estimate failed (T006): notificationId={}, scheduleType={}, exceptionClass={}, message={}",
                    notification.getId(), notification.getScheduleType(), e.getClass().getName(), e.getMessage(), e);
            return result.estimateStatus(TransitNotificationDto.EstimateStatus.UNAVAILABLE)
                    .estimateErrorCode(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE.getCode()).build();
        }
    }
}
