package com.OnETA.common;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExternalApiCallCounterTest {
    @Test
    void schedulerReportsZeroCallsAndCleansUpAfterFailure() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExternalApiCallCounter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ExternalApiCallCounter.runScheduler("실패 실행", () -> {
                        ExternalApiCallCounter.record("KAKAO", "search");
                        throw new IllegalStateException("test");
                    })).isInstanceOf(IllegalStateException.class);
            ExternalApiCallCounter.runScheduler("빈 실행", () -> ExternalApiCallCounter.note("처리 대상 없음"));
            var summaries = appender.list.stream().map(e -> e.getFormattedMessage())
                    .filter(s -> s.contains("[스케줄러 호출량]")).toList();
            assertThat(summaries).hasSize(2);
            assertThat(summaries.get(0)).contains("예외로 중단", "KAKAO | 이번 실행 1회");
            assertThat(summaries.get(1)).contains("KAKAO | 이번 실행 0회", "처리 대상 없음");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void separatesProvidersAndApisAndResetsDailyCountAtKoreanMidnight() {
        Clock clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-15T14:59:59Z"));
        var counter = new ExternalApiCallCounter(clock);
        counter.recordAttempt("ODSAY", "route");
        var second = counter.recordAttempt("ODSAY", "schedule");
        assertThat(second).isEqualTo(new ExternalApiCallCounter.Counts(2, 2, 1, 1));
        assertThat(counter.recordAttempt("KAKAO", "search"))
                .isEqualTo(new ExternalApiCallCounter.Counts(1, 1, 1, 1));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-15T15:00:00Z"));
        assertThat(counter.recordAttempt("ODSAY", "route"))
                .isEqualTo(new ExternalApiCallCounter.Counts(3, 1, 2, 1));
    }

    @Test
    void concurrentAttemptsAreNotLost() {
        var counter = new ExternalApiCallCounter(Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Seoul")));
        IntStream.range(0, 100).parallel().forEach(i -> counter.recordAttempt("KAKAO", "search"));
        assertThat(counter.recordAttempt("KAKAO", "search"))
                .isEqualTo(new ExternalApiCallCounter.Counts(101, 101, 101, 101));
    }
}
