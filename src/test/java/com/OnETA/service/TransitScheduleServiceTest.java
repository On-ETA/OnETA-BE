package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import com.OnETA.entity.*;
import com.OnETA.repository.ScheduleSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransitScheduleServiceTest {
    @Test
    void selectedJustAfterMidnightRemainsOnPreviousOperatingDayEvenWithLaterSnapshot() {
        var service = service("0530", "2330");
        var route = route(10, 20, "1");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        var notification = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        var selected = DATE.plusDays(1).atTime(0, 10);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(
                DATE.atTime(23, 59).atZone(SEOUL).toInstant(), ZoneOffset.UTC));

        service.pinSelectedDeparture(notification, selected.atZone(SEOUL).toOffsetDateTime(), SEOUL);

        var saved = org.mockito.ArgumentCaptor.forClass(ScheduleSnapshot.class);
        verify(snapshotRepo(service)).save(saved.capture());
        assertThat(saved.getValue().getServiceDate()).isEqualTo(DATE);
        assertThat(saved.getValue().getEffectiveDepartureAt()).isEqualTo(selected);
        assertThat(saved.getValue().getSource()).isEqualTo("SELECTED_PREVIEW");

        var later = new ScheduleSnapshot(notification, DATE.plusDays(1),
                NotificationScheduleType.LAST_TRANSIT, "later", DATE.plusDays(1).atTime(23, 0),
                DATE.plusDays(1).atTime(22, 55), DATE.plusDays(1).atTime(22, 0),
                DATE.atTime(23, 59), 30);
        when(snapshotRepo(service).findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                eq(1L), eq(DATE), eq(NotificationScheduleType.LAST_TRANSIT), anyString()))
                .thenReturn(Optional.of(saved.getValue()));
        when(snapshotRepo(service).findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(
                eq(1L), eq(NotificationScheduleType.LAST_TRANSIT), anyString()))
                .thenReturn(Optional.of(later));

        var now = DATE.plusDays(1).atTime(0, 1);
        assertThat(service.hasPendingPreviousLast(notification, now)).isTrue();
        assertThat(service.estimateDeparture(notification, now, SEOUL)).isEqualTo(selected);
    }

    @Test
    void todayPinnedLastWinsOverTomorrowSnapshotWhileItIsCatchable() {
        var service = service("0530", "2330");
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        var now = DATE.atTime(23, 12);
        var today = new ScheduleSnapshot(n, DATE, NotificationScheduleType.LAST_TRANSIT,
                "today", DATE.atTime(23, 22), DATE.atTime(23, 17),
                DATE.atTime(22, 40), now, 37);
        today.useSelectedPreviewSource();
        var tomorrow = new ScheduleSnapshot(n, DATE.plusDays(1), NotificationScheduleType.LAST_TRANSIT,
                "tomorrow", DATE.plusDays(1).atTime(23, 22),
                DATE.plusDays(1).atTime(23, 17), DATE.plusDays(1).atTime(22, 40),
                now, 37);
        when(snapshotRepo(service).findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(
                eq(1L), eq(NotificationScheduleType.LAST_TRANSIT), anyString()))
                .thenReturn(Optional.of(tomorrow));
        when(snapshotRepo(service).findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                eq(1L), eq(DATE), eq(NotificationScheduleType.LAST_TRANSIT), anyString()))
                .thenReturn(Optional.of(today));

        assertThat(service.estimateDeparture(n, now, SEOUL)).isEqualTo(DATE.atTime(23, 22));
        verifyNoInteractions(publicData(service));
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void kakaoSubwayOnlyLastPrefersVerifiedMetroTrainTimesOverAmbiguousTago() {
        TransitApiService transit = mock(TransitApiService.class);
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        var service = new TransitScheduleService(transit, publicData, snapshots,
                new ObjectMapper(), new RestTemplate());
        var metro = mock(SeoulMetroTrainScheduleService.class);
        var tago = mock(TagoSubwayScheduleService.class);
        service.setTagoSubwayScheduleService(tago);
        service.setSeoulMetroTrainScheduleService(metro);
        var six = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("6호선")
                .startStation("상수").endStation("합정").durationMinutes(2).build();
        var two = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("2호선")
                .startStation("합정").endStation("신도림").durationMinutes(9).build();
        var route = TransitDto.RouteOptionResponse.builder().provider("KAKAO")
                .routeId("KAKAO_SANGSU_HAPJEONG_SINDORIM")
                .totalDurationMinutes(37).transferCount(1)
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(9).build(),
                        six,
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(2).build(),
                        two,
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(13).build()))
                .build();
        when(metro.resolve(eq(six), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "상수", "", "SEOUL_METRO:6호선", DATE.atTime(5, 30),
                DATE.atTime(23, 50), 0, "합정", 0));
        when(metro.resolve(eq(two), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "합정", "", "SEOUL_METRO:2호선", DATE.atTime(5, 30),
                DATE.atTime(23, 53), 0, "신도림", 0));

        assertThat(service.previewCurrentLastDeparture(route,
                DATE.atTime(22, 47), new java.util.HashMap<>()))
                .isEqualTo(DATE.atTime(23, 22));
        verify(metro).resolve(eq(six), eq(DATE));
        verify(metro).resolve(eq(two), eq(DATE));
        verifyNoInteractions(tago);
    }

    @Test
    void pinnedLastKeepsLiveSeoulBusEvaluationWhenBindingIsAvailable() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var route = SeoulBusScheduleServiceTest.route();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "bus", DATE.atTime(4, 30), DATE.plusDays(1).atTime(0, 20)));
        var notification = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        var selected = DATE.plusDays(1).atTime(0, 10);
        var pinned = new ScheduleSnapshot(notification, DATE, NotificationScheduleType.LAST_TRANSIT,
                "hash", selected, selected.minusMinutes(5), DATE.atTime(23, 30),
                DATE.atTime(23, 0), 30);
        pinned.useSelectedPreviewSource();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(pinned));

        var decision = service.evaluate(notification, DATE, DATE.atTime(23, 50), SEOUL);

        assertThat(pinned.getSource()).isEqualTo("SEOUL_BUS");
        assertThat(decision.hardDeadlineAt()).isEqualTo(selected);
        verify(snapshotRepo(service)).save(pinned);
    }

    @Test
    void selectedPastLastDepartureIsRejectedBeforeCreatingSnapshot() {
        var service = service("0530", "2330");
        var notification = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        var now = DATE.plusDays(1).atTime(0, 11);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(now.atZone(SEOUL).toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.pinSelectedDeparture(notification,
                DATE.plusDays(1).atTime(0, 10).atZone(SEOUL).toOffsetDateTime(), SEOUL))
                .isInstanceOf(com.OnETA.common.exception.GlobalException.class)
                .hasMessageContaining("경로를 다시 조회");
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void displayEstimateWorksBeforePollingWindowWithoutSavingOrLookingUpRealtime() {
        var service = service("0530", "2330");
        var n = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        assertThat(service.estimateDeparture(n, DATE.atTime(2, 0), SEOUL)).isEqualTo(DATE.atTime(5, 20));
        verify(snapshotRepo(service), never()).save(any());
        verifyNoInteractions(publicData(service));
    }

    @Test
    void displayUsesFuturePersistedDepartureAndAdvancesPastSnapshotToNextServiceDay() {
        var service = service("0530", "2330");
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 10);
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        var snapshot = new ScheduleSnapshot(n, DATE, NotificationScheduleType.LAST_TRANSIT, "hash",
                DATE.plusDays(1).atTime(0, 20), DATE.plusDays(1).atTime(0, 10), DATE.atTime(23, 0), DATE.atTime(12, 0), 30);
        snapshot.updateConnection(DATE.plusDays(1).atTime(0, 15), DATE.plusDays(1).atTime(0, 5), 30, DATE.atTime(23, 50));
        when(snapshotRepo(service).findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(any(), any(), any()))
                .thenReturn(Optional.of(snapshot));

        assertThat(service.estimateDeparture(n, DATE.plusDays(1).atTime(0, 10), SEOUL))
                .isEqualTo(DATE.plusDays(1).atTime(0, 15));
        assertThat(service.estimateDeparture(n, DATE.plusDays(1).atTime(0, 16), SEOUL))
                .isEqualTo(DATE.plusDays(1).atTime(23, 20));

        verifyNoInteractions(publicData(service));
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void midnightSeoulOrdinaryLastUsesCurrentIntervalForPreviewDisplayAndDelivery() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var route = SeoulBusScheduleServiceTest.route();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "bus", DATE.atTime(4, 30), DATE.plusDays(1).atTime(0, 20)));
        var now = DATE.atTime(0, 2);
        // Ten minutes of access walking must leave time to catch the 00:20 bus.
        assertThat(service.previewCurrentLastDeparture(route, now, new java.util.HashMap<>()))
                .isEqualTo(DATE.atTime(0, 10));
        assertThat(service.previewNextDeparture(route, NotificationScheduleType.LAST_TRANSIT, now, SEOUL))
                .isEqualTo(DATE.atTime(0, 10));
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        assertThat(service.estimateDeparture(n, now, SEOUL)).isEqualTo(DATE.atTime(0, 10));
        var decision = service.evaluate(n, DATE, now, SEOUL);
        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(0, 10));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(0, 5));
        verify(seoul, never()).resolve(any(), eq(DATE.minusDays(1)));
    }

    @Test
    void seoulNightProviderIsScheduledAndCanRemainActiveAfterFourAm() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var base = SeoulBusScheduleServiceTest.route();
        var route = base.toBuilder().provider("SEOUL_NIGHT")
                .segments(List.of(base.getSegments().get(1).toBuilder().transitName("N62").nightBus(true).build()))
                .build();
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "night", DATE.atTime(23, 30), DATE.plusDays(1).atTime(5, 10)));
        assertThat(service.previewCurrentLastDeparture(route, DATE.atTime(4, 5), new java.util.HashMap<>()))
                .isEqualTo(DATE.atTime(5, 10));
        assertThat(service.previewCurrentLastDeparture(route, DATE.atTime(12, 0), new java.util.HashMap<>())).isNull();
        verifyNoInteractions(publicData(service));
    }

    @Test
    void currentBusLastIsExcludedWhenAccessWalkWouldMissIt() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "bus", DATE.atTime(4, 30), DATE.plusDays(1).atTime(0, 20)));
        assertThat(service.previewCurrentLastDeparture(SeoulBusScheduleServiceTest.route(),
                DATE.atTime(0, 15), new java.util.HashMap<>())).isNull();
    }

    @Test
    void currentNightBusDoesNotConnectToSubwayThatOnlyReopensInMorning() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        var tago = mock(TagoSubwayScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        service.setTagoSubwayScheduleService(tago);
        var bus = SeoulBusScheduleServiceTest.route().getSegments().get(1);
        var subway = bus.toBuilder().transitType("SUBWAY").durationMinutes(15).build();
        var route = SeoulBusScheduleServiceTest.route().toBuilder().transferCount(1)
                .segments(List.of(bus, subway)).build();
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "night", DATE.atTime(23, 30), DATE.plusDays(1).atTime(3, 10)));
        when(tago.resolve(eq(subway), any())).thenAnswer(i -> {
            LocalDate date = i.getArgument(1);
            return new SeoulBusScheduleService.Schedule("2", "", "subway", date.atTime(5, 30), date.atTime(23, 50));
        });
        assertThat(service.previewCurrentLastDeparture(route, DATE.atTime(0, 2), new java.util.HashMap<>())).isNull();
    }

    @Test
    void upgradeRebuildsLegacyTonightsSnapshotUsingCurrentBusInterval() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var route = SeoulBusScheduleServiceTest.route();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(seoul.resolve(any(), eq(DATE))).thenReturn(new SeoulBusScheduleService.Schedule(
                "1", "01001", "bus", DATE.atTime(4, 30), DATE.plusDays(1).atTime(0, 20)));
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        String oldHash = ReflectionTestUtils.invokeMethod(service, "hash", "route");
        var old = new ScheduleSnapshot(n, DATE, NotificationScheduleType.LAST_TRANSIT, oldHash,
                DATE.atTime(23, 6), DATE.atTime(23, 1), DATE.atTime(22, 0), DATE.atStartOfDay(), 30);
        when(snapshotRepo(service).findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(any(), any(), any()))
                .thenAnswer(i -> oldHash.equals(i.getArgument(2)) ? Optional.of(old) : Optional.empty());
        assertThat(service.estimateDeparture(n, DATE.atTime(0, 2), SEOUL)).isEqualTo(DATE.atTime(0, 10));
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void upgradePreservesLegacySnapshotThatIsStillValidAfterMidnight() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        when(serviceApi(service).readSavedRoute("route")).thenReturn(SeoulBusScheduleServiceTest.route());
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 5);
        String oldHash = ReflectionTestUtils.invokeMethod(service, "hash", "route");
        var old = new ScheduleSnapshot(n, DATE.minusDays(1), NotificationScheduleType.LAST_TRANSIT, oldHash,
                DATE.atTime(0, 20), DATE.atTime(0, 15), DATE.minusDays(1).atTime(23, 0), DATE.atStartOfDay(), 30);
        old.useSeoulBusSource();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(i -> oldHash.equals(i.getArgument(3)) ? Optional.of(old) : Optional.empty());
        when(snapshotRepo(service).findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(any(), any(), any()))
                .thenAnswer(i -> oldHash.equals(i.getArgument(2)) ? Optional.of(old) : Optional.empty());
        assertThat(service.evaluate(n, DATE.minusDays(1), DATE.atTime(0, 2), SEOUL).hardDeadlineAt())
                .isEqualTo(DATE.atTime(0, 20));
        assertThat(service.estimateDeparture(n, DATE.atTime(0, 2), SEOUL)).isEqualTo(DATE.atTime(0, 20));
        verifyNoInteractions(seoul);
    }

    @Test
    void currentOdsayBusWindowUsesBothBoundariesFromOneApiResponse() {
        var service = service("23:30", "03:10");
        var route = route(10, 20, "1");
        assertThat(service.previewCurrentLastDeparture(route, DATE.atTime(0, 2), new java.util.HashMap<>()))
                .isEqualTo(DATE.atTime(3, 0));
    }

    @Test
    void endedOdsayBusAdvancesOnlyAfterCurrentWindowHasBeenChecked() {
        var service = service("05:30", "23:06");
        var route = route(10, 20, "1");
        assertThat(service.previewNextDeparture(route, NotificationScheduleType.LAST_TRANSIT,
                DATE.atTime(0, 2), SEOUL)).isEqualTo(DATE.atTime(22, 56));
    }

    private static final LocalDate DATE = LocalDate.of(2026, 8, 27);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void mapsOdsaySubwayDayFromServiceDate() {
        var service = service("0530", "2330");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "odsayDay",
                LocalDate.of(2026, 8, 29))).isEqualTo("2");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "odsayDay",
                LocalDate.of(2026, 8, 30))).isEqualTo("3");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "odsayDay",
                LocalDate.of(2026, 8, 31))).isEqualTo("1");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "odsayDay",
                LocalDate.of(2026, 10, 9))).isEqualTo("3");
    }

    @Test
    void hangulDayOdsayScheduleRequestUsesHolidayDayAndCorrectAccessWalk() {
        var transit = mock(TransitApiService.class);
        var publicData = mock(PublicDataTransitService.class);
        var snapshots = mock(ScheduleSnapshotRepository.class);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("subwayPathSchedule")))
                .andExpect(queryParam("DAY", "3"))
                .andExpect(queryParam("MODE", "4"))
                .andExpect(queryParam("SID", "SANGSU"))
                .andExpect(queryParam("EID", "HAPJEONG"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"departureTime":"2350"}}]}}
                        """, MediaType.APPLICATION_JSON));

        var service = new TransitScheduleService(transit, publicData, snapshots, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "http://odsay/v1/api");

        var subway = TransitDto.RouteSegment.builder().transitType("SUBWAY")
                .transitName("6호선").startStation("상수").endStation("합정")
                .odsayStartStationId("SANGSU").odsayEndStationId("HAPJEONG")
                .durationMinutes(2).build();
        var route = TransitDto.RouteOptionResponse.builder().provider("ODSAY")
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(9).build(),
                        subway,
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(1).build()))
                .build();
        var date = LocalDate.of(2026, 10, 9);

        assertThat(service.previewDepartureForServiceDate(route,
                NotificationScheduleType.LAST_TRANSIT, date, new java.util.HashMap<>()))
                .isEqualTo(date.atTime(23, 41));
        server.verify();
    }

    @Test
    void lastSubwayTransferUsesConnectedOdsayJourneyInsteadOfTwoIndependentLastTrains() {
        var transit = mock(TransitApiService.class);
        var publicData = mock(PublicDataTransitService.class);
        var snapshots = mock(ScheduleSnapshotRepository.class);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("subwayPathSchedule")))
                .andExpect(queryParam("SID", "600"))
                .andExpect(queryParam("EID", "201"))
                .andExpect(queryParam("MID", "601"))
                .andExpect(queryParam("MODE", "4"))
                .andExpect(queryParam("DAY", "3"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"departureTime":"23:40:00"},
                          "subPath":[
                            {"movingType":1,"startID":600,"endID":601,"laneName":"6호선",
                             "departureTime":"23:40:00","arrivalTime":"23:42:00"},
                            {"movingType":2,"sectionTime":2},
                            {"movingType":1,"startID":200,"endID":201,"laneName":"2호선",
                             "departureTime":"23:48:00","arrivalTime":"23:57:00"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var service = new TransitScheduleService(transit, publicData, snapshots, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "http://odsay/v1/api");
        LocalDate holiday = LocalDate.of(2026, 10, 9);

        var six = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("6호선")
                .odsayStartStationId("600").odsayEndStationId("601")
                .startStation("상수").endStation("합정").durationMinutes(2).build();
        var two = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("2호선")
                .odsayStartStationId("200").odsayEndStationId("201")
                .startStation("합정").endStation("신도림").durationMinutes(9).build();
        var route = TransitDto.RouteOptionResponse.builder().provider("ODSAY")
                .routeId("SANGSU_HAPJEONG_SINDORIM").totalDurationMinutes(37)
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(9).build(),
                        six,
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(2).build(),
                        two,
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(13).build()))
                .build();
        // Both normal preview and current-service LAST must preserve the same
        // connected departure; they may never silently jump to another route.
        assertThat(service.previewCurrentLastDeparture(route,
                holiday.atTime(20, 0), new java.util.HashMap<>()))
                .isEqualTo(holiday.atTime(23, 26));
        server.verify();
    }

    @Test
    void kakaoSubwayFallsBackToSeoulMetroWhenTagoWeekendScheduleIsEmpty() {
        TransitApiService transit = mock(TransitApiService.class);
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitScheduleService service = new TransitScheduleService(
                transit, publicData, snapshots, new ObjectMapper(), new RestTemplate());

        var tago = mock(TagoSubwayScheduleService.class);
        service.setTagoSubwayScheduleService(tago);
        when(tago.resolve(any(), any())).thenThrow(new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));

        var seoulMetro = mock(SeoulMetroTrainScheduleService.class);
        service.setSeoulMetroTrainScheduleService(seoulMetro);
        LocalDate saturday = LocalDate.of(2026, 10, 3);
        when(seoulMetro.resolve(any(), eq(saturday))).thenReturn(
                new SeoulBusScheduleService.Schedule(
                        "신도림", "", "SEOUL_METRO:2호선",
                        saturday.atTime(5, 32), saturday.plusDays(1).atTime(0, 18),
                        0, "합정", 0));

        assertThat(service.previewDepartureForServiceDate(
                kakaoSubwayRoute(), NotificationScheduleType.FIRST_TRANSIT,
                saturday, new java.util.HashMap<>()))
                .isEqualTo(saturday.atTime(5, 27));

        verify(seoulMetro).resolve(any(), eq(saturday));
    }

    @Test
    void kakaoSubwaySeoulMetroFailureBecomesT006AfterTagoFailure() {
        TransitApiService transit = mock(TransitApiService.class);
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitScheduleService service = new TransitScheduleService(
                transit, publicData, snapshots, new ObjectMapper(), new RestTemplate());

        var tago = mock(TagoSubwayScheduleService.class);
        service.setTagoSubwayScheduleService(tago);
        when(tago.resolve(any(), any())).thenThrow(new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));

        var seoulMetro = mock(SeoulMetroTrainScheduleService.class);
        service.setSeoulMetroTrainScheduleService(seoulMetro);
        when(seoulMetro.resolve(any(), any())).thenThrow(new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));

        assertThatThrownBy(() -> service.previewDepartureForServiceDate(
                kakaoSubwayRoute(), NotificationScheduleType.FIRST_TRANSIT,
                LocalDate.of(2026, 10, 3), new java.util.HashMap<>()))
                .isInstanceOfSatisfying(com.OnETA.common.exception.GlobalException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));
    }

    @Test
    void connectedFirstUsesActualNextSubwayInsteadOfOnlyItsDailyFirst() {
        TransitApiService transit = mock(TransitApiService.class);
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitScheduleService service = new TransitScheduleService(
                transit, publicData, snapshots, new ObjectMapper(), new RestTemplate());

        var seoulBus = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoulBus);
        var tago = mock(TagoSubwayScheduleService.class);
        service.setTagoSubwayScheduleService(tago);
        var metro = mock(SeoulMetroTrainScheduleService.class);
        service.setSeoulMetroTrainScheduleService(metro);

        LocalDate day = LocalDate.of(2026, 10, 4);
        var route = kakaoBusSubwayRoute();
        when(seoulBus.resolve(any(), eq(day))).thenReturn(new SeoulBusScheduleService.Schedule(
                "bus", "", "night-bus", day.atTime(22, 30), day.plusDays(1).atTime(1, 0)));
        when(tago.resolve(any(), eq(day))).thenReturn(new SeoulBusScheduleService.Schedule(
                "subway", "", "1호선", day.atTime(5, 30), day.plusDays(1).atTime(0, 30)));
        when(metro.firstTripAtOrAfter(any(), eq(day), any())).thenReturn(Optional.of(
                new SeoulMetroTrainScheduleService.TripWindow(day.atTime(23, 5), day.atTime(23, 55))));

        assertThat(service.previewDepartureForServiceDate(
                route, NotificationScheduleType.FIRST_TRANSIT, day, new java.util.HashMap<>()))
                .isEqualTo(day.atTime(22, 17));

        verify(metro).firstTripAtOrAfter(any(), eq(day), eq(day.atTime(22, 50)));
    }

    @Test
    void connectedFirstRejectsOvernightTransferWait() {
        TransitApiService transit = mock(TransitApiService.class);
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitScheduleService service = new TransitScheduleService(
                transit, publicData, snapshots, new ObjectMapper(), new RestTemplate());

        var seoulBus = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoulBus);
        var tago = mock(TagoSubwayScheduleService.class);
        service.setTagoSubwayScheduleService(tago);
        var metro = mock(SeoulMetroTrainScheduleService.class);
        service.setSeoulMetroTrainScheduleService(metro);

        LocalDate day = LocalDate.of(2026, 10, 4);
        var route = kakaoBusSubwayRoute();
        when(seoulBus.resolve(any(), eq(day))).thenReturn(new SeoulBusScheduleService.Schedule(
                "bus", "", "night-bus", day.atTime(22, 30), day.plusDays(1).atTime(1, 0)));
        when(tago.resolve(any(), eq(day))).thenReturn(new SeoulBusScheduleService.Schedule(
                "subway", "", "1호선", day.atTime(5, 30), day.plusDays(1).atTime(0, 30)));
        when(metro.firstTripAtOrAfter(any(), eq(day), any())).thenReturn(Optional.of(
                new SeoulMetroTrainScheduleService.TripWindow(
                        day.plusDays(1).atTime(5, 30), day.plusDays(1).atTime(6, 20))));

        assertThatThrownBy(() -> service.previewDepartureForServiceDate(
                route, NotificationScheduleType.FIRST_TRANSIT, day, new java.util.HashMap<>()))
                .isInstanceOfSatisfying(com.OnETA.common.exception.GlobalException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED));
    }

    @Test
    void seoulSingleBusUsesStationTimeMinusAccessWalkAndNoOdsayOrRealtime() {
        TransitScheduleService service = service("05:30", "23:30");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var route = SeoulBusScheduleServiceTest.route();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(seoul.resolve(route, DATE)).thenReturn(new SeoulBusScheduleService.Schedule(
                "100000001", "01001", "100100088", DATE.atTime(5, 30), DATE.plusDays(1).atTime(0, 30)));
        var first = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(5, 10), SEOUL);
        assertThat(first.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(first.scheduledAt()).isEqualTo(DATE.atTime(5, 10));
        var last = service.evaluate(notification(NotificationScheduleType.LAST_TRANSIT, 10), DATE, DATE.atTime(23, 0), SEOUL);
        assertThat(last.scheduledAt()).isEqualTo(DATE.plusDays(1).atTime(0, 10));
        verifyNoInteractions(publicData(service));
        var saved = org.mockito.ArgumentCaptor.forClass(ScheduleSnapshot.class);
        verify(snapshotRepo(service), times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(s -> assertThat(s.getSource()).isEqualTo("SEOUL_BUS"));
    }

    @Test
    void previousDaySeoulSnapshotWorksAfterMidnightWithoutFetchingYesterday() {
        TransitScheduleService service = service("05:30", "23:30");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 10);
        var snapshot = new ScheduleSnapshot(n, DATE, NotificationScheduleType.LAST_TRANSIT, "hash",
                DATE.plusDays(1).atTime(0, 20), DATE.plusDays(1).atTime(0, 10),
                DATE.atTime(23, 0), DATE.atTime(12, 0), 30);
        snapshot.useSeoulBusSource();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(snapshot));
        assertThat(service.evaluate(n, DATE, DATE.plusDays(1).atTime(0, 10), SEOUL).scheduledAt())
                .isBeforeOrEqualTo(DATE.plusDays(1).atTime(0, 10));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.evaluate(n, DATE, DATE.plusDays(1).atTime(0, 10), SEOUL)).isNull();
        verifyNoInteractions(seoul, publicData(service));
    }

    @Test
    void calculatesFirstUsingMaxCandidateAndOffset() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        TransitDto.RouteOptionResponse route = route(10, 20, "1");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(5, 10));
    }

    @Test
    void calculatesFirstUsingMaxOfMultipleReminderOffsets() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, List.of(5, 15, 30)),
                DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(4, 50));
    }

    @Test
    void calculatesLastUsingMaxReminderOffsetForEarliestSchedulingWindow() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.LAST_TRANSIT, List.of(5, 10, 30)),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(23, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(22, 50));
    }

    @Test
    void firstRecoveryIsOneCandidateAndUsesNextBoardingDeadline() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 480, "TEST"));

        TransitScheduleService.Decision decision = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(5, 32), SEOUL);

        assertThat(decision.recovery()).isTrue();
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.hardDeadlineAt()).isEqualTo(DATE.atTime(5, 40));
        verify(publicData(service), times(1)).findArrival(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recoveryTimeoutCanBeRetriedBeforeFiveMinuteDeadline() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 480, "TEST"));

        ArrivalNotification notification = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        assertThat(service.evaluate(notification, DATE,
                DATE.atTime(5, 32), SEOUL)).isNull();
        TransitScheduleService.Decision retry = service.evaluate(notification, DATE,
                DATE.atTime(5, 34), SEOUL);

        assertThat(retry.recovery()).isTrue();
        verify(publicData(service), times(2)).findArrival(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reusesSameDaySnapshotWithoutCallingBaseApiAgain() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0)); return invocation.getArgument(0);
        });
        ArrivalNotification n = notification(NotificationScheduleType.LAST_TRANSIT, 10);

        service.evaluate(n, DATE, DATE.atTime(20, 0), SEOUL);
        service.evaluate(n, DATE, DATE.atTime(20, 1), SEOUL);

        verify(snapshotRepo(service), times(1)).save(any());
    }

    @Test
    void firstSafetyIsMonotonicAndDoesNotMoveBackOnLateObservation() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0)); return invocation.getArgument(0);
        });
        ArrivalNotification n = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 60, "TEST"))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 600, "TEST"));

        TransitScheduleService.Decision early = service.evaluate(n, DATE, DATE.atTime(4, 40), SEOUL);
        TransitScheduleService.Decision late = service.evaluate(n, DATE, DATE.atTime(4, 41), SEOUL);

        assertThat(early.scheduledAt()).isEqualTo(DATE.atTime(5, 3));
        assertThat(late.scheduledAt()).isEqualTo(DATE.atTime(5, 3));
    }

    @Test
    void matchesOfficialBusLocalBlIdWhenOdsayBusIdDoesNotMatch() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":999,\"busLocalBlID\":\"100100088\",\"busNo\":\"603\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}");
        TransitDto.RouteOptionResponse route = TransitDto.RouteOptionResponse.builder().totalDurationMinutes(20)
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(10)
                        .odsayStartStationId("193778").odsayRouteId("1168")
                        .localRouteId("100100088").transitName("603").build())).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
    }

    @Test
    void usesBusLastTimeForLastTransit() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busNo\":\"603\",\"busFirstTime\":\"05:30\",\"busLastTime\":\"23:30\"}]}}");
        TransitDto.RouteOptionResponse route = TransitDto.RouteOptionResponse.builder().totalDurationMinutes(20)
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(10)
                        .odsayStartStationId("193778").odsayRouteId("1168")
                        .localRouteId("100100088").transitName("603").build())).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.LAST_TRANSIT, 10), DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(23, 20));
    }

    @Test
    void parsesBusTimesAtOrAfterMidnightWithServiceDateRollover() {
        TransitScheduleService service = service("04:30", "23:40");

        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "23:40", DATE))
                .isEqualTo(DATE.atTime(23, 40));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "24:00", DATE))
                .isEqualTo(DATE.plusDays(1).atStartOfDay());
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "24:36", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(0, 36));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "25:15", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(1, 15));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "27:35", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(3, 35));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "28:10", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(4, 10));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", null, DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "abc", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "29:99", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "-1:00", DATE)).isNull();
    }

    @Test
    void keepsFourDigitOdsayTimesAndCalculatesLastCandidateAcrossDateRollover() {
        TransitScheduleService service = service("04:30", "2515");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.LAST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.plusDays(1).atTime(1, 5));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.plusDays(1).atTime(0, 55));
    }

    @Test
    void preservesDateRolloverForFirstTransitCandidates() {
        TransitScheduleService service = service("2515", "23:40");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.plusDays(1).atTime(1, 5));
    }

    @Test
    void treatsOdsayErrorArrayAsApiErrorInsteadOfEmptyLanes() {
        TransitScheduleService service = serviceWithResponse(
                "{\"error\":[{\"code\":\"500\",\"message\":\"[ApiKeyAuthFailed] ApiKey authentication failed.\"}]}" );
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ODsay schedule API error")
                .hasMessageContaining("ApiKeyAuthFailed");
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void reportsOnlyWhetherOdsayApiKeyIsConfigured() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[]}}" );

        ReflectionTestUtils.setField(service, "apiKey", "");
        assertThat(service.isApiKeyConfigured()).isFalse();

        ReflectionTestUtils.setField(service, "apiKey", "configured-test-value");
        assertThat(service.isApiKeyConfigured()).isTrue();
    }

    @Test
    void trimsApiKeyBeforeScheduleRequest() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}",
                "test-key");
        ReflectionTestUtils.setField(service, "apiKey", "  test-key  ");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
    }

    @Test
    void sendsEmptyApiKeyForBlankValueWithoutThrowingDuringNormalization() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}",
                "");
        ReflectionTestUtils.setField(service, "apiKey", "  ");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.isApiKeyConfigured()).isFalse();
        assertThat(service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL))
                .isNotNull();
    }

    // Regression tests for the transfer failures found during the reuse audit.
    @Test
    void transferFirstStartsWithFirstBusAndIncludesTransferWait() {
        var service = transferService("0530", "2330", "0600", "2340");
        var result = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(4, 0), SEOUL);
        // Access 10 + bus A 20 + transfer walk 5 => B prefix 35.
        assertThat(result.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(result.baseDepartureAt().plusMinutes(10)).isEqualTo(DATE.atTime(5, 30));
        assertThat(result.estimatedDuration()).isEqualTo(60);
    }

    @Test
    void transferLastUsesEarlyConservativeEstimateForUnknownIntermediateDeparture() {
        var service = transferService("0530", "2330", "0600", "2340");
        var decision = service.evaluate(notification(NotificationScheduleType.LAST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);
        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(22, 45));
        verify(snapshotRepo(service)).save(any());
    }

    @Test
    void transferWithMissingSecondScheduleCannotProduceASnapshot() {
        var service = transferService("0530", "2330", "", "");
        assertThatThrownBy(() -> service.evaluate(notification(NotificationScheduleType.LAST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL))
                .isInstanceOfSatisfying(com.OnETA.common.exception.GlobalException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("T005"));
        verify(snapshotRepo(service), never()).save(any());
    }

    private TransitScheduleService transferService(String firstA, String lastA, String firstB, String lastB) {
        var transit = mock(TransitApiService.class);
        var publicData = mock(PublicDataTransitService.class);
        var snapshots = mock(ScheduleSnapshotRepository.class);
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var times = List.of(List.of(firstA, lastA), List.of(firstB, lastB));
        for (int i = 0; i < times.size(); i++) {
            server.expect(queryParam("stationID", "station" + i))
                    .andRespond(withSuccess(("{\"result\":{\"lane\":[{\"busID\":\"%s\",\"busFirstTime\":\"%s\",\"busLastTime\":\"%s\"}]}}")
                            .formatted(i, times.get(i).get(0), times.get(i).get(1)), MediaType.APPLICATION_JSON));
        }
        var service = new TransitScheduleService(transit, publicData, snapshots, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "http://odsay/v1/api");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshots.findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(transit.readSavedRoute("route")).thenReturn(TransitDto.RouteOptionResponse.builder()
                .provider("ODSAY").totalDurationMinutes(55).transferCount(1).segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(20)
                                .odsayStartStationId("station0").odsayRouteId("0").build(),
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(5).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(20)
                                .odsayStartStationId("station1").odsayRouteId("1").build())).build());
        SERVICES.put(service, new Deps(transit, publicData, snapshots));
        return service;
    }

    @Test
    void seoulTransferPollsNearDepartureButStillAlertsConservativelyWhenDataIsMissing() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var base = SeoulBusScheduleServiceTest.route();
        var bus = base.getSegments().get(1);
        var route = base.toBuilder().transferCount(1).segments(List.of(base.getSegments().get(0), bus,
                base.getSegments().get(2), bus)).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        var a = new SeoulBusScheduleService.Schedule("1", "1", "1", DATE.atTime(5, 30), DATE.atTime(23, 30));
        var b = new SeoulBusScheduleService.Schedule("2", "2", "2", DATE.atTime(6, 0), DATE.atTime(23, 50));
        when(seoul.resolveRoute(route, DATE)).thenReturn(List.of(a, b));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(i -> { saved.set(i.getArgument(0)); return saved.get(); });
        var n = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        assertThat(service.evaluate(n, DATE, DATE.atTime(2, 0), SEOUL)).isNull();
        verify(seoul, never()).arrivals(any(), any());
        when(seoul.arrivals(eq(a), any())).thenReturn(List.of(new SeoulBusScheduleService.LiveBus(
                DATE.atTime(5, 30), DATE.atTime(5, 50), false, "A")));
        when(seoul.arrivals(eq(b), any())).thenReturn(List.of(new SeoulBusScheduleService.LiveBus(
                DATE.atTime(6, 0), DATE.atTime(6, 15), false, "B")));
        var decision = service.evaluate(n, DATE, DATE.atTime(5, 10), SEOUL);
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(5, 5));
        assertThat(decision.estimatedDuration()).isEqualTo(60);
        assertThat(saved.get().getProviderDetails()).contains("stationId");
        when(seoul.arrivals(eq(b), any())).thenReturn(List.of());
        assertThat(service.evaluate(n, DATE, DATE.atTime(5, 11), SEOUL).scheduledAt()).isEqualTo(DATE.atTime(5, 5));
        when(seoul.arrivals(eq(a), any())).thenThrow(new IllegalStateException("temporary outage"));
        assertThat(service.evaluate(n, DATE, DATE.atTime(5, 12), SEOUL).scheduledAt()).isEqualTo(DATE.atTime(5, 5));
        verify(seoul, times(1)).resolveRoute(route, DATE);
        verifyNoInteractions(publicData(service));
    }

    @Test
    void seoulTransferReusesPersistedBindingsAfterMidnightWithoutFetchingTodaysTimetable() {
        var service = service("0530", "2330");
        var seoul = mock(SeoulBusScheduleService.class);
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);
        var base = SeoulBusScheduleServiceTest.route();
        var bus = base.getSegments().get(1);
        var route = base.toBuilder().transferCount(1).segments(List.of(bus, bus)).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        var n = notification(NotificationScheduleType.LAST_TRANSIT, 10);
        var a = new SeoulBusScheduleService.Schedule("1", "1", "1", DATE.atTime(5, 30), DATE.plusDays(1).atTime(0, 20));
        var b = new SeoulBusScheduleService.Schedule("2", "2", "2", DATE.atTime(6, 0), DATE.plusDays(1).atTime(0, 40));
        var snapshot = new ScheduleSnapshot(n, DATE, NotificationScheduleType.LAST_TRANSIT, "hash",
                DATE.plusDays(1).atTime(0, 20), DATE.plusDays(1).atTime(0, 10),
                DATE.atTime(23, 0), DATE.atTime(12, 0), 30);
        snapshot.useSeoulTransferSource(new ObjectMapper().writeValueAsString(List.of(a, b)));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(snapshot));
        when(seoul.arrivals(eq(a), any())).thenReturn(List.of(new SeoulBusScheduleService.LiveBus(
                DATE.plusDays(1).atTime(0, 20), DATE.plusDays(1).atTime(0, 30), true, "A")));
        when(seoul.arrivals(eq(b), any())).thenReturn(List.of(new SeoulBusScheduleService.LiveBus(
                DATE.plusDays(1).atTime(0, 40), DATE.plusDays(1).atTime(0, 50), true, "B")));
        assertThat(service.evaluate(n, DATE, DATE.plusDays(1).atTime(0, 10), SEOUL).scheduledAt())
                .isEqualTo(DATE.plusDays(1).atTime(0, 10));
        verify(seoul, never()).resolveRoute(any(), any());
    }

    private TransitScheduleService serviceWithResponse(String response) {
        return serviceWithResponse(response, "test");
    }

    private TransitScheduleService serviceWithResponse(String response, String expectedApiKey) {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitApiService transit = mock(TransitApiService.class);
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("busStationInfo")))
                .andExpect(queryParam("apiKey", expectedApiKey))
                .andExpect(header("Referer", "http://localhost:8080/"))
                .andExpect(header("Origin", "http://localhost:8080"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        TransitScheduleService result = new TransitScheduleService(transit, publicData, snapshots, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(result, "apiKey", "test");
        ReflectionTestUtils.setField(result, "scheduleBaseUrl", "http://odsay/v1/api");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SERVICES.put(result, new Deps(transit, publicData, snapshots));
        return result;
    }

    private TransitScheduleService service(String first, String last) {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitApiService transit = mock(TransitApiService.class);
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("busStationInfo")))
                .andExpect(header("Referer", "http://localhost:8080/"))
                .andExpect(header("Origin", "http://localhost:8080"))
                .andRespond(withSuccess(("{\"result\":{\"lane\":[{\"busID\":1,\"busLocalBlID\":\"1\",\"busNo\":\"1\",\"busFirstTime\":\"%s\",\"busLastTime\":\"%s\"}]}}" ).formatted(first, last), MediaType.APPLICATION_JSON));
        TransitScheduleService result = new TransitScheduleService(transit, publicData, snapshots, mapper, rest);
        ReflectionTestUtils.setField(result, "apiKey", "test");
        ReflectionTestUtils.setField(result, "scheduleBaseUrl", "http://odsay/v1/api");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SERVICES.put(result, new Deps(transit, publicData, snapshots));
        return result;
    }

    private static final java.util.Map<TransitScheduleService, Deps> SERVICES = new java.util.IdentityHashMap<>();
    private TransitApiService serviceApi(TransitScheduleService s) { return SERVICES.get(s).transit; }
    private PublicDataTransitService publicData(TransitScheduleService s) { return SERVICES.get(s).publicData; }
    private ScheduleSnapshotRepository snapshotRepo(TransitScheduleService s) { return SERVICES.get(s).snapshots; }
    private record Deps(TransitApiService transit, PublicDataTransitService publicData, ScheduleSnapshotRepository snapshots) {}

    private ArrivalNotification notification(NotificationScheduleType type, int offset) {
        return notification(type, List.of(offset));
    }

    private ArrivalNotification notification(NotificationScheduleType type, List<Integer> offsets) {
        ArrivalNotification n = mock(ArrivalNotification.class);
        when(n.getId()).thenReturn(1L); when(n.getScheduleType()).thenReturn(type);
        when(n.getIsActive()).thenReturn(true);
        when(n.getRouteDetails()).thenReturn("route");
        when(n.getReminderOffsetMinutes()).thenReturn(offsets.get(0));
        when(n.getReminderOffsetMinutesList()).thenReturn(offsets);
        return n;
    }

    private TransitDto.RouteOptionResponse kakaoBusSubwayRoute() {
        return TransitDto.RouteOptionResponse.builder()
                .provider("KAKAO")
                .routeId("KAKAO_connected_first")
                .totalDurationMinutes(87)
                .transferCount(1)
                .segments(List.of(
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(8)
                                .startStation("").endStation("버스정류장").stations(List.of()).build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("BUS").transitName("H6B(심야)").durationMinutes(18)
                                .startStation("동탄119안전센터").endStation("서동탄")
                                .stations(List.of(
                                        TransitDto.RouteStation.builder().name("동탄119안전센터").sequence(1).build(),
                                        TransitDto.RouteStation.builder().name("서동탄").sequence(2).build()))
                                .build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(1)
                                .startStation("서동탄").endStation("서동탄역").stations(List.of()).build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("SUBWAY").transitName("1호선").durationMinutes(55)
                                .startStation("서동탄").endStation("신도림")
                                .stations(List.of(
                                        TransitDto.RouteStation.builder().name("서동탄").sequence(1).build(),
                                        TransitDto.RouteStation.builder().name("신도림").sequence(2).build()))
                                .build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(5)
                                .startStation("신도림").endStation("").stations(List.of()).build()))
                .build();
    }

    private TransitDto.RouteOptionResponse kakaoSubwayRoute() {
        return TransitDto.RouteOptionResponse.builder()
                .provider("KAKAO")
                .routeId("KAKAO_test")
                .totalDurationMinutes(10)
                .transferCount(0)
                .segments(List.of(
                        TransitDto.RouteSegment.builder()
                                .transitType("SUBWAY")
                                .transitName("2호선")
                                .durationMinutes(10)
                                .startStation("신도림")
                                .endStation("합정")
                                .startX(126.89161209)
                                .startY(37.50822039)
                                .endX(126.91445633)
                                .endY(37.54991226)
                                .stations(List.of(
                                        TransitDto.RouteStation.builder().name("신도림").sequence(1).build(),
                                        TransitDto.RouteStation.builder().name("문래").sequence(2).build(),
                                        TransitDto.RouteStation.builder().name("영등포구청").sequence(3).build(),
                                        TransitDto.RouteStation.builder().name("당산").sequence(4).build(),
                                        TransitDto.RouteStation.builder().name("합정").sequence(5).build()))
                                .build()))
                .build();
    }

    private TransitDto.RouteOptionResponse route(int walk, int bus, String id) {
        return TransitDto.RouteOptionResponse.builder().routeId("r").totalDurationMinutes(walk + bus)
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(walk).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(bus)
                                .odsayStartStationId("station").odsayRouteId(id).localRouteId(id)
                                .localCityCode("1000").localStationId("station").arsId("ars")
                                .transitName("1").scheduledWaitMinutes(8).build()))
                .build();
    }
}
