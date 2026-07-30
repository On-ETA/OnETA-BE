package com.HomeRun.service;

import com.HomeRun.dto.NotificationDto;
import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.Notification;
import com.HomeRun.entity.User;
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

    @Transactional
    public Long createArrivalNotification(String email, NotificationDto.CreateArrivalRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        String routeName = request.getRouteName();
        if (routeName == null || routeName.trim().isEmpty()) {
            int currentCount = notificationRepository.findAllByUserId(user.getId()).size();
            routeName = "경로" + (currentCount + 1);
        }

        ArrivalNotification notification = new ArrivalNotification(
                user,
                routeName,
                request.getReminderOffsetMinutes(),
                request.getRepeatDays(),
                request.getTargetArrivalTime(),
                request.getRouteDetails()
        );

        return arrivalNotificationRepository.save(notification).getId();
    }

    public List<NotificationDto.ArrivalResponse> getArrivalNotifications(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        return arrivalNotificationRepository.findAllByUserId(user.getId())
                .stream()
                .map(NotificationDto.ArrivalResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateArrivalNotification(String email, Long id, NotificationDto.UpdateArrivalRequest request) {
        if (request.getRouteName() == null && request.getTargetArrivalTime() == null
                && request.getReminderOffsetMinutes() == null && request.getRepeatDays() == null
                && request.getRouteDetails() == null) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE, "수정할 값이 하나도 없습니다.");
        }

        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);

        notification.updateCommonInfo(request.getRouteName(), request.getReminderOffsetMinutes(), request.getRepeatDays());
        notification.updateArrivalInfo(request.getTargetArrivalTime(), request.getRouteDetails());
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
        ArrivalNotification notification = getArrivalNotificationByEmailAndId(email, id);
        notification.toggleActive(request.getIsActive());
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
