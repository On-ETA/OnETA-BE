package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.NotificationDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSavedRouteValidationTest {
    private static final String ROUTE = """
            {"routeId":"ROUTE_665d14214e38f18b","totalDurationMinutes":27,
             "segments":[{"transitType":"WALK","durationMinutes":1,"stations":[]},
             {"transitType":"BUS","transitName":"603","durationMinutes":25,
              "startStation":"홍대입구역","endStation":"서울역버스환승센터(6번승강장)",
              "odsayRouteId":"1168","odsayStartStationId":"193778","odsayEndStationId":"104664"},
             {"transitType":"WALK","durationMinutes":1,"stations":[]}]}
            """;
    private final ArrivalNotificationRepository arrivals = mock(ArrivalNotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final User user = mock(User.class);
    // Real JSON parsing reproduces malformed legacy rows rather than mocking their validation.
    private final TransitApiService transit = new TransitApiService(mock(PublicDataTransitService.class),
            new ObjectMapper(), mock(RestTemplate.class));
    private final NotificationService service = new NotificationService(arrivals,
            mock(NotificationRepository.class), users, new RepeatDaysService(), transit, mock(NotificationDeliveryRepository.class), mock(TransitScheduleService.class));

    @BeforeEach
    void setup() {
        when(user.getId()).thenReturn(1L);
        when(users.findForNotificationByEmail("me")).thenReturn(Optional.of(user));
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"{\"origin\":\"A\",\"destination\":\"B\"}", "{\"segments\":[]}", "{\"segments\":null}", "not-json"})
    void legacyRouteCannotRejectValidTransitRegistration(String legacy) {
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of(saved(10L, legacy)));
        service.createArrivalNotification("me", request(ROUTE));
        verify(arrivals).save(argThat(n -> ROUTE.equals(n.getRouteDetails())));
    }

    @Test
    void acceptsFrontendWrappedRouteDetailsForNormalSchedule() {
        String wrapped = """
                {"route":%s,
                 "origin":"출발지","destination":"도착지",
                 "originAddress":"경기도 화성시","destinationAddress":"서울특별시 마포구"}
                """.formatted(ROUTE);
        when(arrivals.findAllForDuplicateCheckByUserId(1L)).thenReturn(List.of());

        var request = new NotificationDto.CreateArrivalRequest();
        request.setRouteName("출근");
        request.setScheduleType(NotificationScheduleType.NORMAL);
        request.setTargetArrivalTime(LocalTime.of(13, 25));
        request.setReminderOffsetMinutes(List.of(5, 10));
        request.setRepeatDays(List.of());
        request.setRouteDetails(wrapped);

        service.createArrivalNotification("me", request);

        verify(arrivals).save(argThat(n -> wrapped.equals(n.getRouteDetails())));
        var parsed = transit.readSavedRoute(wrapped);
        assertThat(parsed.getSegments()).hasSize(3);
        assertThat(parsed.getOriginAddress()).isEqualTo("경기도 화성시");
        assertThat(parsed.getDestinationAddress()).isEqualTo("서울특별시 마포구");
    }

    @Test
    void stillRejectsActualDuplicateAfterSkippingLegacyRoute() {
        var duplicate = saved(11L, ROUTE);
        duplicate.toggleActive(false);
        when(arrivals.findAllForDuplicateCheckByUserId(1L))
                .thenReturn(List.of(saved(10L, "{}"), duplicate));
        assertThatThrownBy(() -> service.createArrivalNotification("me", request(ROUTE)))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_ALREADY_EXISTS));
        verify(arrivals, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"segments\":[]}", "not-json"})
    void rejectsInvalidNewRouteEvenWithoutExistingNotifications(String invalid) {
        assertThatThrownBy(() -> service.createArrivalNotification("me", request(invalid)))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(arrivals, never()).save(any());
    }

    @Test
    void allowsRepairingOwnRouteDespiteAnotherLegacyRow() {
        var own = saved(10L, "{}");
        when(arrivals.findById(10L)).thenReturn(Optional.of(own));
        when(arrivals.findAllForDuplicateCheckByUserId(1L))
                .thenReturn(List.of(own, saved(11L, "{}")));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setRouteDetails(ROUTE);
        service.updateArrivalNotification("me", 10L, update);
        assertThat(own.getRouteDetails()).isEqualTo(ROUTE);
    }

    private ArrivalNotification saved(long id, String details) {
        var n = new ArrivalNotification(user, "legacy", List.of(5), 0,
                LocalTime.of(9, 0), details, NotificationScheduleType.NORMAL);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    private NotificationDto.CreateArrivalRequest request(String details) {
        var request = new NotificationDto.CreateArrivalRequest();
        request.setRouteName("603 첫차");
        request.setScheduleType(NotificationScheduleType.FIRST_TRANSIT);
        request.setReminderOffsetMinutes(List.of(1, 5, 10));
        request.setRouteDetails(details);
        return request;
    }
}
