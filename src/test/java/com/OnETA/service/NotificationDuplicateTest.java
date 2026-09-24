package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
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

class NotificationDuplicateTest {
    private final ArrivalNotificationRepository arrivals = mock(ArrivalNotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TransitApiService transit = mock(TransitApiService.class);
    private final User user = mock(User.class);
    private final NotificationService service = new NotificationService(arrivals,
            mock(NotificationRepository.class), users, new RepeatDaysService(), transit);

    @BeforeEach
    void setup() {
        when(user.getId()).thenReturn(1L);
        when(users.findForNotificationByEmail("me")).thenReturn(Optional.of(user));
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void rejectsSameRouteDespiteChangedSearchIdEstimatesAndNotificationSettings() {
        var saved = existing(10L, "saved");
        saved.toggleActive(false);
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(saved));
        when(transit.readSavedRoute("saved")).thenReturn(route("100"));
        var refreshed = route("100");
        refreshed.setRouteId("different-search-id");
        refreshed.setTotalDurationMinutes(70);
        refreshed.setRealTimeDurationMinutes(80);
        refreshed.setTotalCost(2000);
        refreshed.getSegments().get(0).setRealTimeArrivalSeconds(60);
        refreshed.getSegments().get(0).setDurationMinutes(50);
        when(transit.readSavedRoute("new")).thenReturn(refreshed);
        var request = request();
        request.setRouteName("another name");
        request.setRepeatDays(List.of("MON"));
        request.setScheduleType(NotificationScheduleType.LAST_TRANSIT);
        assertThatThrownBy(() -> service.createArrivalNotification("me", request))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_ALREADY_EXISTS));
        verify(arrivals, never()).save(any());
    }

    @Test
    void allowsSameEndpointsWithDifferentBusLine() {
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(existing(10L, "saved")));
        when(transit.readSavedRoute("saved")).thenReturn(route("100"));
        when(transit.readSavedRoute("new")).thenReturn(route("200"));
        service.createArrivalNotification("me", request());
        verify(arrivals).save(any());
    }

    @Test
    void comparesOnlyCurrentUsersNotifications() {
        service.createArrivalNotification("me", request());
        verify(arrivals, times(2)).findAllForDuplicateCheckByUserId(1L);
        verify(arrivals, never()).findAll();
        verify(arrivals).save(any());
    }

    @Test
    void rejectsUpdateToAnotherSavedRouteWithoutMutatingNotification() {
        var own = existing(10L, "old");
        when(arrivals.findById(10L)).thenReturn(Optional.of(own));
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(own, existing(11L, "saved")));
        when(transit.readSavedRoute("saved")).thenReturn(route("100"));
        when(transit.readSavedRoute("new")).thenReturn(route("100"));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setRouteDetails("new");
        update.setRouteName("changed");
        assertThatThrownBy(() -> service.updateArrivalNotification("me", 10L, update))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_ALREADY_EXISTS));
        assertThat(own.getRouteDetails()).isEqualTo("old");
        assertThat(own.getName()).isEqualTo("saved name");
    }

    @Test
    void allowsUpdatingOwnRouteAndDifferentRoute() {
        var own = existing(10L, "old");
        when(arrivals.findById(10L)).thenReturn(Optional.of(own));
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(own));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setRouteDetails("old");
        service.updateArrivalNotification("me", 10L, update);
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(own, existing(11L, "saved")));
        when(transit.readSavedRoute("saved")).thenReturn(route("100"));
        when(transit.readSavedRoute("new")).thenReturn(route("200"));
        update.setRouteDetails("new");
        service.updateArrivalNotification("me", 10L, update);
        assertThat(own.getRouteDetails()).isEqualTo("new");
    }

    @Test
    void identityDistinguishesStopsEndpointsAndTransferOrder() {
        var original = route("100");
        var changed = route("100");
        changed.getSegments().get(0).setEndStation("different stop");
        assertThat(NotificationRouteIdentity.of(changed)).isNotEqualTo(NotificationRouteIdentity.of(original));
        changed = route("100");
        changed.setDestinationAddress("different destination");
        assertThat(NotificationRouteIdentity.of(changed)).isNotEqualTo(NotificationRouteIdentity.of(original));
        var first = original.getSegments().get(0);
        var second = route("200").getSegments().get(0);
        original.setSegments(List.of(first, second));
        changed.setDestinationAddress(original.getDestinationAddress());
        changed.setSegments(List.of(second, first));
        assertThat(NotificationRouteIdentity.of(changed)).isNotEqualTo(NotificationRouteIdentity.of(original));
    }

    @Test
    void identityDistinguishesIntermediateStopsOnTheSameLine() {
        var original = route("100");
        var changed = route("100");
        original.getSegments().get(0).setStations(List.of(
                TransitDto.RouteStation.builder().name("via A").build()));
        changed.getSegments().get(0).setStations(List.of(
                TransitDto.RouteStation.builder().name("via B").build()));
        assertThat(NotificationRouteIdentity.of(changed)).isNotEqualTo(NotificationRouteIdentity.of(original));
    }

    private ArrivalNotification existing(long id, String details) {
        var result = new ArrivalNotification(user, "saved name", List.of(10), 0,
                LocalTime.of(9, 0), details, NotificationScheduleType.NORMAL);
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private NotificationDto.CreateArrivalRequest request() {
        var result = new NotificationDto.CreateArrivalRequest();
        result.setRouteName("new name");
        result.setTargetArrivalTime(LocalTime.of(10, 0));
        result.setReminderOffsetMinutes(List.of(5));
        result.setRouteDetails("new");
        return result;
    }

    private TransitDto.RouteOptionResponse route(String line) {
        return TransitDto.RouteOptionResponse.builder().routeId("search-id").originAddress("origin")
                .destinationAddress("destination").totalDurationMinutes(30).segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("BUS").transitName(line)
                                .startStation("start").endStation("end").durationMinutes(20).build())).build();
    }
}
