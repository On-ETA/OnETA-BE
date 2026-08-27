package com.HomeRun.service;

import com.HomeRun.dto.NotificationDto;
import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.Notification;
import com.HomeRun.entity.User;
import com.HomeRun.entity.NotificationScheduleType;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.NotificationRepository;
import com.HomeRun.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final RepeatDaysService repeatDaysService;
    private final TransitApiService transitApiService;

    @Transactional
    public Long createArrivalNotification(String email, NotificationDto.CreateArrivalRequest request) {
        validateCreateRequest(request);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        String routeName = request.getRouteName();
        if (routeName == null || routeName.trim().isEmpty()) {
            int currentCount = notificationRepository.findAllByUserId(user.getId()).size();
            routeName = "경로" + (currentCount + 1);
        }

        int repeatDays = repeatDaysService.toMask(request.getRepeatDays());
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
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        return arrivalNotificationRepository.findAllByUserId(user.getId())
                .stream()
                .map(notification -> NotificationDto.ArrivalResponse.fromEntity(notification, repeatDaysService))
                .collect(Collectors.toList());
    }

    public NotificationDto.ArrivalDetailResponse getArrivalNotificationDetail(String email, Long id) {
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        return NotificationDto.ArrivalDetailResponse.builder()
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
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE, "수정할 값이 없습니다.");
        }
        if (request.getRouteName() == null && request.getTargetArrivalTime() == null
                && request.getReminderOffsetMinutes() == null && request.getRepeatDays() == null
                && request.getRouteDetails() == null && request.getScheduleType() == null) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE, "수정할 값이 하나도 없습니다.");
        }

        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);

        validateReminderOffsets(request.getReminderOffsetMinutes(), false);

        Integer requestedRepeatDays = request.getRepeatDays() == null
                ? null
                : repeatDaysService.toMask(request.getRepeatDays());
        notification.updateCommonInfo(request.getRouteName(), request.getReminderOffsetMinutes());
        if (requestedRepeatDays != null) notification.updateRepeatDays(requestedRepeatDays);
        notification.updateArrivalInfo(request.getTargetArrivalTime(), request.getRouteDetails());
        notification.updateScheduleType(request.getScheduleType());
    }

    @Transactional
    public void deleteArrivalNotifications(String email, List<Long> ids) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        List<Notification> notifications = notificationRepository.findAllById(ids);
        for (Notification noti : notifications) {
            if (!noti.getUser().getId().equals(user.getId())) {
                throw new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.HANDLE_ACCESS_DENIED);
            }
        }
        notificationRepository.deleteAll(notifications);
    }

    @Transactional
    public void toggleStatus(String email, Long id, NotificationDto.ToggleStatusRequest request) {
        if (request == null || request.getIsActive() == null) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE, "isActive는 필수입니다.");
        }
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        notification.toggleActive(request.getIsActive());
    }

    private void validateCreateRequest(NotificationDto.CreateArrivalRequest request) {
        if (request == null || request.getTargetArrivalTime() == null
                || request.getReminderOffsetMinutes() == null
                || request.getRouteDetails() == null || request.getRouteDetails().isBlank()) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "목표 도착시간, 미리 알림 시간, 경로 정보는 필수입니다.");
        }
        validateReminderOffsets(request.getReminderOffsetMinutes(), true);
    }

    private void validateReminderOffsets(List<Integer> offsets, boolean required) {
        if (offsets == null && !required) return;
        if (offsets == null || offsets.isEmpty()
                || offsets.stream().anyMatch(offset -> offset == null
                || !List.of(1, 3, 5, 10, 15, 30, 60).contains(offset))) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "미리 알림 시간은 1, 3, 5, 10, 15, 30, 60분 중 하나 이상 선택해야 합니다.");
        }
    }

    private ArrivalNotification getArrivalNotificationByEmailAndId(String email, Long id) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        ArrivalNotification notification = arrivalNotificationRepository.findById(id)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.getUser().getId().equals(user.getId())) {
            throw new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.HANDLE_ACCESS_DENIED);
        }
        return notification;
    }
}
