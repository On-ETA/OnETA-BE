package com.OnETA.service;

import com.OnETA.controller.ScheduleNotificationController;
import com.OnETA.dto.NotificationDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import com.OnETA.common.exception.GlobalException;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationCategoryTest {
    @Test void listsOnlyOwnedCategoryAndKeepsFirstAndLastDistinguishable() {
        var users = mock(UserRepository.class);
        var arrivals = mock(ArrivalNotificationRepository.class);
        var user = mock(User.class); when(user.getId()).thenReturn(1L);
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        var records = Arrays.stream(NotificationScheduleType.values()).map(type -> new ArrivalNotification(user,
                "test", List.of(10), 0, LocalTime.of(9, 0), "{}", type)).toList();
        when(arrivals.findAllByUserId(1L)).thenReturn(records);
        var service = new NotificationService(arrivals, mock(NotificationRepository.class), users,
                new RepeatDaysService(), mock(TransitApiService.class));
        assertThat(service.getArrivalNotifications("me", NotificationCategory.SCHEDULE))
                .extracting(NotificationDto.ArrivalResponse::getScheduleType).containsExactly(NotificationScheduleType.NORMAL);
        assertThat(service.getArrivalNotifications("me", NotificationCategory.TRANSIT))
                .extracting(NotificationDto.ArrivalResponse::getScheduleType)
                .containsExactlyInAnyOrder(NotificationScheduleType.FIRST_TRANSIT, NotificationScheduleType.LAST_TRANSIT);
        assertThat(service.getArrivalNotifications("me", NotificationCategory.TRANSIT))
                .allSatisfy(n -> assertThat(n.getTargetArrivalTime()).isNull());
        assertThatThrownBy(() -> service.getArrivalNotifications("other", NotificationCategory.TRANSIT))
                .isInstanceOf(GlobalException.class);
    }

    @Test void splitEndpointsEnforceTypesAndDoNotRequireArrivalTimeForTransit() {
        var service = mock(NotificationService.class);
        var controller = new ScheduleNotificationController(service);
        for (var type : List.of(NotificationScheduleType.FIRST_TRANSIT, NotificationScheduleType.LAST_TRANSIT)) {
            var request = new NotificationDto.CreateArrivalRequest(); request.setScheduleType(type);
            controller.createTransit(() -> "me", request);
            verify(service).createArrivalNotification("me", request);
            assertThat(request.getTargetArrivalTime()).isNull();
            assertThatThrownBy(() -> controller.createSchedule(() -> "me", request)).isInstanceOf(GlobalException.class);
        }
        var missing = new NotificationDto.CreateArrivalRequest();
        assertThatThrownBy(() -> controller.createTransit(() -> "me", missing)).isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> controller.transit(null)).isInstanceOf(GlobalException.class);
        controller.schedules(() -> "me"); controller.transit(() -> "me");
        verify(service).getArrivalNotifications("me", NotificationCategory.SCHEDULE);
        verify(service).getArrivalNotifications("me", NotificationCategory.TRANSIT);
    }
}
