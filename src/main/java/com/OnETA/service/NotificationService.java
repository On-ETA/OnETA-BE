package com.OnETA.service;

import com.OnETA.dto.NotificationDto;
import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.User;
import com.OnETA.entity.NotificationScheduleType;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.repository.NotificationRepository;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final RepeatDaysService repeatDaysService;
    private final TransitApiService transitApiService;
    private final com.OnETA.repository.NotificationDeliveryRepository deliveryRepository;

    @Transactional
    public Long createArrivalNotification(String email, NotificationDto.CreateArrivalRequest request) {
        validateCreateRequest(request);
        User user = userRepository.findForNotificationByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));

        boolean transit = isTransit(request.getScheduleType());
        if (!transit) validateCategoryLimit(user.getId(), request.getScheduleType());
        validateUniqueRoute(user.getId(), null, request.getRouteDetails(), transit);
        // The user lock serializes concurrent replacements, including the legacy /arrival API.
        // FIRST and LAST are independent slots: creating one hard-deletes only the same schedule type.
        if (transit) deleteCurrentTransit(user.getId(), request.getScheduleType());

        String routeName = transit ? transitName(request.getScheduleType()) : request.getRouteName();
        if (routeName == null || routeName.trim().isEmpty()) {
            int currentCount = notificationRepository.findAllByUserId(user.getId()).size();
            routeName = "경로" + (currentCount + 1);
        }

        int repeatDays = isTransit(request.getScheduleType()) ? 0
                : repeatDaysService.toMask(request.getRepeatDays());
        ArrivalNotification notification = new ArrivalNotification(
                user,
                routeName,
                request.getReminderOffsetMinutes(),
                repeatDays,
                request.getTargetArrivalTime(),
                request.getRouteDetails(),
                request.getScheduleType() == null ? NotificationScheduleType.NORMAL : request.getScheduleType()
        );

        return arrivalNotificationRepository.save(notification).getId();
    }

    public List<NotificationDto.ArrivalResponse> getArrivalNotifications(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));

        return arrivalNotificationRepository.findAllByUserId(user.getId())
                .stream()
                .filter(n -> !n.isTransitArchived())
                .map(notification -> NotificationDto.ArrivalResponse.fromEntity(notification, repeatDaysService))
                .collect(Collectors.toList());
    }

    public List<NotificationDto.ArrivalResponse> getArrivalNotifications(String email,
            com.OnETA.entity.NotificationCategory category) {
        return getArrivalNotifications(email).stream()
                .filter(n -> category == null || n.getCategory() == category).toList();
    }

    public NotificationDto.ArrivalDetailResponse getArrivalNotificationDetail(String email, Long id) {
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        return NotificationDto.ArrivalDetailResponse.builder()
                .category(com.OnETA.entity.NotificationCategory.of(notification.getScheduleType()))
                .notificationId(notification.getId())
                .routeName(notification.getName())
                .targetArrivalTime(notification.getTargetArrivalTime())
                .reminderOffsetMinutes(notification.getReminderOffsetMinutesList())
                .repeatDays(repeatDaysService.toDays(notification.getRepeatDays()))
                .isActive(notification.getIsActive())
                .scheduleType(notification.getScheduleType())
                .route(transitApiService.readSavedRoute(notification.getRouteDetails()))
                .build();
    }

    @Transactional
    public void updateArrivalNotification(String email, Long id, NotificationDto.UpdateArrivalRequest request) {
        if (request == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "수정할 값이 없습니다.");
        }
        if (request.getRouteName() == null && request.getTargetArrivalTime() == null
                && request.getReminderOffsetMinutes() == null && request.getRepeatDays() == null
                && request.getRouteDetails() == null && request.getScheduleType() == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "수정할 값이 하나도 없습니다.");
        }

        userRepository.findForNotificationByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);

        if (request.getRouteDetails() != null) {
            validateUniqueRoute(notification.getUser().getId(), id, request.getRouteDetails(), false);
        }
        validateReminderOffsets(request.getReminderOffsetMinutes(), false);
        NotificationScheduleType effectiveType = request.getScheduleType() == null
                ? notification.getScheduleType() : request.getScheduleType();
        if (com.OnETA.entity.NotificationCategory.of(effectiveType)
                != com.OnETA.entity.NotificationCategory.of(notification.getScheduleType())) {
            validateCategoryLimit(notification.getUser().getId(), effectiveType);
        }
        validateTargetArrivalTime(effectiveType, request.getTargetArrivalTime() == null
                ? notification.getTargetArrivalTime() : request.getTargetArrivalTime());
        validateRouteSchedule(effectiveType, request.getRouteDetails() == null
                ? notification.getRouteDetails() : request.getRouteDetails());

        Integer requestedRepeatDays = isTransit(effectiveType) ? Integer.valueOf(0)
                : request.getRepeatDays() == null ? null : repeatDaysService.toMask(request.getRepeatDays());
        notification.updateCommonInfo(isTransit(effectiveType) ? transitName(effectiveType) : request.getRouteName(),
                request.getReminderOffsetMinutes());
        if (requestedRepeatDays != null) notification.updateRepeatDays(requestedRepeatDays);
        notification.updateArrivalInfo(request.getTargetArrivalTime(), request.getRouteDetails());
        notification.updateScheduleType(request.getScheduleType());
    }

    @Transactional
    public void deleteTransitNotification(String email, Long id) {
        if (id == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "삭제할 첫차·막차 알림 ID가 필요합니다.");
        }

        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        if (!isTransit(notification.getScheduleType())) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "첫차·막차 알림만 삭제할 수 있습니다.");
        }

        List<Long> targetIds = List.of(notification.getId());
        deliveryRepository.expireReplacedTransitDeliveries(targetIds);
        deleteNotificationRows(targetIds);
    }

    private void deleteCurrentTransit(Long userId, NotificationScheduleType scheduleType) {
        var current = arrivalNotificationRepository.findAllForDuplicateCheckByUserId(userId).stream()
                .filter(n -> !n.isTransitArchived() && isTransit(n.getScheduleType()))
                .filter(n -> scheduleType == null || n.getScheduleType() == scheduleType)
                .toList();
        if (current.isEmpty()) return;

        List<Long> targetIds = current.stream().map(ArrivalNotification::getId).toList();
        // Stop any pending/sending outbox work before physically removing the old setting.
        deliveryRepository.expireReplacedTransitDeliveries(targetIds);
        deleteNotificationRows(targetIds);
    }

    private void deleteNotificationRows(List<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return;
        // FK child tables first, then JOINED inheritance child/parent rows.
        notificationRepository.deleteScheduleSnapshotsByIds(targetIds);
        notificationRepository.deleteDeliveriesByIds(targetIds);
        notificationRepository.deleteReminderOffsetsByIds(targetIds);
        notificationRepository.deleteArrivalRowsByIds(targetIds);
        notificationRepository.deleteRowsByIds(targetIds);
    }

    private String transitName(NotificationScheduleType type) {
        return type == NotificationScheduleType.FIRST_TRANSIT ? "첫차 알림" : "막차 알림";
    }

    @Transactional
    public void deleteArrivalNotifications(String email, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "삭제할 알림 ID가 없습니다.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));

        List<ArrivalNotification> notifications = arrivalNotificationRepository.findAllById(ids);
        long distinctIdCount = ids.stream().distinct().count();
        if (notifications.size() != distinctIdCount) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        for (ArrivalNotification notification : notifications) {
            if (!notification.getUser().getId().equals(user.getId())) {
                throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.HANDLE_ACCESS_DENIED);
            }
        }

        List<Long> targetIds = notifications.stream()
                .map(ArrivalNotification::getId)
                .toList();

        deleteNotificationRows(targetIds);
    }

    @Transactional
    public void toggleStatus(String email, Long id, NotificationDto.ToggleStatusRequest request) {
        if (request == null || request.getIsActive() == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "isActive는 필수입니다.");
        }
        userRepository.findForNotificationByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        notification.toggleActive(request.getIsActive());
    }

    private void validateCategoryLimit(Long userId, NotificationScheduleType type) {
        var category = com.OnETA.entity.NotificationCategory.of(type);
        long count = arrivalNotificationRepository.findAllForDuplicateCheckByUserId(userId).stream()
                .filter(n -> !n.isTransitArchived())
                .filter(n -> com.OnETA.entity.NotificationCategory.of(n.getScheduleType()) == category)
                .count();
        int limit = isTransit(type) ? 1 : 5;
        if (count >= limit) {
            String label = category == com.OnETA.entity.NotificationCategory.SCHEDULE ? "일반" : "첫차·막차";
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.NOTIFICATION_LIMIT_EXCEEDED,
                    label + " 알림은 최대 " + limit + "개까지 등록할 수 있습니다.");
        }
    }

    private void validateUniqueRoute(Long userId, Long excludedId, String details, boolean replacingTransit) {
        // Validate incoming data even when this is the user's first saved route.
        var identity = NotificationRouteIdentity.of(transitApiService.readSavedRoute(details));
        var existing = arrivalNotificationRepository.findAllForDuplicateCheckByUserId(userId).stream()
                .filter(n -> !n.isTransitArchived())
                .filter(n -> !replacingTransit || !isTransit(n.getScheduleType()))
                .filter(n -> excludedId == null || !excludedId.equals(n.getId())).toList();
        for (var notification : existing) {
            NotificationRouteIdentity savedIdentity;
            try {
                savedIdentity = NotificationRouteIdentity.of(
                        transitApiService.readSavedRoute(notification.getRouteDetails()));
            } catch (com.OnETA.common.exception.GlobalException e) {
                if (e.getErrorCode() != com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE) throw e;
                // Legacy records without a usable route cannot participate in route comparison.
                log.warn("Skipping invalid saved route during duplicate check: notificationId={}",
                        notification.getId());
                continue;
            }
            if (identity.equals(savedIdentity)) {
                throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.NOTIFICATION_ALREADY_EXISTS);
            }
        }
    }

    private void validateCreateRequest(NotificationDto.CreateArrivalRequest request) {
        if (request == null || request.getReminderOffsetMinutes() == null
                || request.getRouteDetails() == null || request.getRouteDetails().isBlank()) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "미리 알림 시간, 경로 정보는 필수입니다.");
        }
        validateTargetArrivalTime(request.getScheduleType(), request.getTargetArrivalTime());
        validateRouteSchedule(request.getScheduleType(), request.getRouteDetails());
        validateReminderOffsets(request.getReminderOffsetMinutes(), true);
    }

    private void validateRouteSchedule(NotificationScheduleType type, String details) {
        if (type == null || type == NotificationScheduleType.NORMAL) return;
        // Registration/update validates only the persisted route payload.
        // NIGHT_ONLY is a route characteristic, so reject it here without
        // depending on transient timetable API availability.
        var route = transitApiService.readSavedRoute(details);
        if (TransitRouteClassifier.isNightOnlyRoute(route)) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_ONLY_ROUTE);
        }
    }

    private void validateTargetArrivalTime(NotificationScheduleType type, java.time.LocalTime time) {
        if ((type == null || type == NotificationScheduleType.NORMAL) && time == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "일반 경로(NORMAL)는 목표 도착시간이 필수입니다.");
        }
    }

    private boolean isTransit(NotificationScheduleType type) {
        return type == NotificationScheduleType.FIRST_TRANSIT || type == NotificationScheduleType.LAST_TRANSIT;
    }

    private void validateReminderOffsets(List<Integer> offsets, boolean required) {
        if (offsets == null && !required) return;
        if (offsets == null || offsets.isEmpty()
                || offsets.stream().anyMatch(offset -> offset == null
                || !List.of(1, 3, 5, 10, 15, 30, 60).contains(offset))) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "미리 알림 시간은 1, 3, 5, 10, 15, 30, 60분 중 하나 이상 선택해야 합니다.");
        }
    }

    private ArrivalNotification getArrivalNotificationByEmailAndId(String email, Long id) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));

        ArrivalNotification notification = arrivalNotificationRepository.findById(id)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.getUser().getId().equals(user.getId())) {
            throw new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.HANDLE_ACCESS_DENIED);
        }
        if (notification.isTransitArchived()) {
            throw new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return notification;
    }
}
