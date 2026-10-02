package com.OnETA.service;

import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.dto.NotificationDto;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransitNotificationReplacementTest {
    private final ArrivalNotificationRepository arrivals = mock(ArrivalNotificationRepository.class);
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final NotificationRepository all = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TransitApiService transit = mock(TransitApiService.class);
    private final User user = mock(User.class);
    private final NotificationService service = new NotificationService(arrivals, all, users, new RepeatDaysService(), transit, deliveries);

    @BeforeEach void setup() {
        when(user.getId()).thenReturn(1L);
        when(users.findForNotificationByEmail("me")).thenReturn(Optional.of(user));
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        when(transit.readSavedRoute(anyString())).thenAnswer(i -> TransitDto.RouteOptionResponse.builder()
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("BUS")
                        .transitName(i.getArgument(0)).durationMinutes(20).build())).build());
        when(arrivals.save(any())).thenAnswer(i -> {
            ArrivalNotification n = i.getArgument(0);
            ReflectionTestUtils.setField(n, "id", 100L);
            return n;
        });
    }

    @Test void creatingLastKeepsExistingFirst() {
        var first = notification(10L, NotificationScheduleType.FIRST_TRANSIT, "same");
        var normal = notification(11L, NotificationScheduleType.NORMAL, "normal");
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(first, normal));
        var request = request();
        request.setRouteDetails("same");

        assertThat(service.createArrivalNotification("me", request)).isEqualTo(100L);

        assertThat(first.isTransitArchived()).isFalse();
        assertThat(first.getIsActive()).isTrue();
        assertThat(normal.isTransitArchived()).isFalse();
        verify(deliveries, never()).expireReplacedTransitDeliveries(anyList());
        verify(all, never()).deleteRowsByIds(anyList());
        verify(arrivals).save(argThat(n -> n.getScheduleType() == NotificationScheduleType.LAST_TRANSIT
                && n.getName().equals("막차 알림") && n.getRepeatDays() == 0
                && Boolean.TRUE.equals(n.getIsActive())));
    }

    @Test void replacingLastHardDeletesOnlyExistingLastAndCancelsItsOutbox() {
        var first = notification(10L, NotificationScheduleType.FIRST_TRANSIT, "first");
        var last = notification(11L, NotificationScheduleType.LAST_TRANSIT, "last");
        var normal = notification(12L, NotificationScheduleType.NORMAL, "normal");
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(first, last, normal));

        assertThat(service.createArrivalNotification("me", request())).isEqualTo(100L);

        assertThat(first.isTransitArchived()).isFalse();
        assertThat(first.getIsActive()).isTrue();
        assertThat(normal.isTransitArchived()).isFalse();
        verify(deliveries).expireReplacedTransitDeliveries(List.of(11L));
        verify(all).deleteScheduleSnapshotsByIds(List.of(11L));
        verify(all).deleteDeliveriesByIds(List.of(11L));
        verify(all).deleteReminderOffsetsByIds(List.of(11L));
        verify(all).deleteArrivalRowsByIds(List.of(11L));
        verify(all).deleteRowsByIds(List.of(11L));
    }

    @Test void invalidReplacementLeavesCurrentNotificationAndOutboxUnchanged() {
        var old = notification(10L, NotificationScheduleType.FIRST_TRANSIT, "saved");
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(old));
        when(transit.readSavedRoute("new")).thenReturn(TransitDto.RouteOptionResponse.builder().segments(List.of()).build());
        assertThatThrownBy(() -> service.createArrivalNotification("me", request())).isInstanceOf(GlobalException.class);
        assertThat(old.isTransitArchived()).isFalse();
        assertThat(old.getIsActive()).isTrue();
        verifyNoInteractions(deliveries);
        verify(arrivals, never()).save(any());
    }

    @Test void singleDeleteHardDeletesCurrentAndIsIdempotent() {
        var old = notification(10L, NotificationScheduleType.LAST_TRANSIT, "saved");
        when(arrivals.findAllForDuplicateCheckByUserId(1L))
                .thenReturn(List.of(old))
                .thenReturn(List.of());

        service.deleteCurrentTransitNotification("me");
        service.deleteCurrentTransitNotification("me");

        verify(deliveries, times(1)).expireReplacedTransitDeliveries(List.of(10L));
        verify(all, times(1)).deleteScheduleSnapshotsByIds(List.of(10L));
        verify(all, times(1)).deleteDeliveriesByIds(List.of(10L));
        verify(all, times(1)).deleteReminderOffsetsByIds(List.of(10L));
        verify(all, times(1)).deleteArrivalRowsByIds(List.of(10L));
        verify(all, times(1)).deleteRowsByIds(List.of(10L));
    }

    @Test void legacyEndpointCannotReactivateArchivedNotification() {
        var old = notification(10L, NotificationScheduleType.FIRST_TRANSIT, "saved");
        old.archiveTransit();
        when(arrivals.findById(10L)).thenReturn(Optional.of(old));
        var request = new NotificationDto.ToggleStatusRequest(); request.setIsActive(true);
        assertThatThrownBy(() -> service.toggleStatus("me", 10L, request)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
        assertThat(old.getIsActive()).isFalse();
    }

    private ArrivalNotification notification(Long id, NotificationScheduleType type, String route) {
        var n = new ArrivalNotification(user, "old name", List.of(5), 0, LocalTime.NOON, route, type);
        ReflectionTestUtils.setField(n, "id", id); return n;
    }

    private NotificationDto.CreateArrivalRequest request() {
        var r = new NotificationDto.CreateArrivalRequest(); r.setRouteName("ignored legacy name");
        r.setRouteDetails("new"); r.setScheduleType(NotificationScheduleType.LAST_TRANSIT);
        r.setReminderOffsetMinutes(List.of(1, 3, 5)); return r;
    }
}
