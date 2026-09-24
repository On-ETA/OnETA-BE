package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.NotificationDto;
import com.OnETA.dto.TransitDto;
import com.OnETA.dto.bus.DepotNotificationRequestDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationLimitTest {
    private final UserRepository users = mock(UserRepository.class);
    private final User user = mock(User.class);
    private final ArrivalNotificationRepository arrivals = mock(ArrivalNotificationRepository.class);
    private final TransitApiService transit = mock(TransitApiService.class);
    private final NotificationService service = new NotificationService(arrivals,
            mock(NotificationRepository.class), users, new RepeatDaysService(), transit);
    private final UserBusRepository buses = mock(UserBusRepository.class);
    private final DepotNotificationRepository depots = mock(DepotNotificationRepository.class);
    private final SeoulBusRouteRepository routes = mock(SeoulBusRouteRepository.class);
    private final DepotNotificationService depotService = new DepotNotificationService(
            mock(FcmService.class), users, buses, depots, routes);

    @BeforeEach
    void setup() {
        when(user.getId()).thenReturn(1L);
        when(users.findForNotificationByEmail("me")).thenReturn(Optional.of(user));
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transit.readSavedRoute(anyString())).thenAnswer(i -> TransitDto.RouteOptionResponse.builder()
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("BUS")
                        .transitName(i.getArgument(0)).build())).build());
        when(routes.existsById("route")).thenReturn(true);
        when(buses.save(any())).thenAnswer(i -> i.getArgument(0));
        when(depots.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @ParameterizedTest
    @EnumSource(NotificationScheduleType.class)
    void fifthAllowedSixthRejectedIncludingDisabled(NotificationScheduleType type) {
        var saved = new ArrayList<>(notifications(type, 4));
        saved.get(0).toggleActive(false);
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(saved);
        service.createArrivalNotification("me", request(type));
        verify(arrivals).save(any());
        saved.add(notification(type, 5));
        assertLimit(() -> service.createArrivalNotification("me", request(type)));
        verify(arrivals).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = NotificationScheduleType.class, names = {"FIRST_TRANSIT", "LAST_TRANSIT"})
    void firstAndLastShareFiveSlots(NotificationScheduleType type) {
        var saved = new ArrayList<>(notifications(NotificationScheduleType.FIRST_TRANSIT, 3));
        saved.addAll(notifications(NotificationScheduleType.LAST_TRANSIT, 2));
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(saved);
        assertLimit(() -> service.createArrivalNotification("me", request(type)));
        verify(arrivals, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(NotificationScheduleType.class)
    void otherCategoryDoesNotConsumeSlots(NotificationScheduleType type) {
        var other = type == NotificationScheduleType.NORMAL
                ? NotificationScheduleType.FIRST_TRANSIT : NotificationScheduleType.NORMAL;
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(notifications(other, 5));
        service.createArrivalNotification("me", request(type));
        verify(arrivals).save(any());
    }

    @ParameterizedTest
    @EnumSource(NotificationScheduleType.class)
    void categoryChangeCannotExceedLimit(NotificationScheduleType target) {
        var source = target == NotificationScheduleType.NORMAL
                ? NotificationScheduleType.FIRST_TRANSIT : NotificationScheduleType.NORMAL;
        var own = notification(source, 100);
        when(arrivals.findById(100L)).thenReturn(Optional.of(own));
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(notifications(target, 5));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setScheduleType(target);
        update.setTargetArrivalTime(LocalTime.NOON);
        assertLimit(() -> service.updateArrivalNotification("me", 100L, update));
        assertThat(own.getScheduleType()).isEqualTo(source);
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(notifications(target, 4));
        service.updateArrivalNotification("me", 100L, update);
        assertThat(own.getScheduleType()).isEqualTo(target);
    }

    @Test
    void firstToLastAndRenamingAtLimitRemainAllowed() {
        var own = notification(NotificationScheduleType.FIRST_TRANSIT, 100);
        when(arrivals.findById(100L)).thenReturn(Optional.of(own));
        when(arrivals.findAllForDuplicateCheckByUserId(1L))
                .thenReturn(notifications(NotificationScheduleType.FIRST_TRANSIT, 5));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setScheduleType(NotificationScheduleType.LAST_TRANSIT);
        update.setRouteName("renamed");
        service.updateArrivalNotification("me", 100L, update);
        assertThat(own.getScheduleType()).isEqualTo(NotificationScheduleType.LAST_TRANSIT);
        assertThat(own.getName()).isEqualTo("renamed");
    }

    @Test
    void depotFifthAllowedSixthRejectedEvenForExistingBusWithoutNotification() {
        var saved = new ArrayList<>(depotNotifications(4));
        when(depots.findAllForRegistrationByUser(user)).thenReturn(saved);
        depotService.setDepotNotification("me", depotRequest());
        verify(depots).save(any());
        saved.add(depotNotifications(5).get(4));
        assertLimit(() -> depotService.setDepotNotification("me", depotRequest()));
        var orphanBus = UserBus.builder().user(user).build();
        ReflectionTestUtils.setField(orphanBus, "id", 99L);
        when(buses.findByUserAndRouteIdAndDirection(user, "route", null)).thenReturn(Optional.of(orphanBus));
        assertLimit(() -> depotService.setDepotNotification("me", depotRequest()));
        verify(depots).save(any());
    }

    @Test
    void existingDepotCanBeReenabledAtLimit() {
        var saved = depotNotifications(5);
        when(depots.findAllForRegistrationByUser(user)).thenReturn(saved);
        when(buses.findByUserAndRouteIdAndDirection(user, "route", null))
                .thenReturn(Optional.of(saved.get(0).getUserBus()));
        depotService.setDepotNotification("me", depotRequest());
        assertThat(saved.get(0).isActive()).isTrue();
        verify(depots, never()).save(any());
        verify(buses, never()).save(any());
    }

    private List<DepotNotification> depotNotifications(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            var bus = UserBus.builder().user(user).build();
            ReflectionTestUtils.setField(bus, "id", (long) i);
            return DepotNotification.builder().userBus(bus).active(false).build();
        }).toList();
    }

    private DepotNotificationRequestDto depotRequest() {
        var request = new DepotNotificationRequestDto();
        request.setRouteId("route");
        return request;
    }

    private List<ArrivalNotification> notifications(NotificationScheduleType type, int count) {
        return IntStream.range(0, count).mapToObj(i -> notification(type, i)).toList();
    }

    private ArrivalNotification notification(NotificationScheduleType type, long id) {
        var notification = new ArrivalNotification(user, "saved", List.of(5), 0,
                LocalTime.NOON, "saved-" + id, type);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    private NotificationDto.CreateArrivalRequest request(NotificationScheduleType type) {
        var request = new NotificationDto.CreateArrivalRequest();
        request.setRouteDetails("new-route");
        request.setRouteName("new");
        request.setReminderOffsetMinutes(List.of(5));
        request.setTargetArrivalTime(LocalTime.NOON);
        request.setScheduleType(type);
        return request;
    }

    private void assertLimit(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_LIMIT_EXCEEDED));
    }
}
