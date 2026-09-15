package com.OnETA.common;

import lombok.extern.slf4j.Slf4j;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** Process-local request attempts, not the provider's billing or quota usage. */
@Slf4j
public final class ExternalApiCallCounter {
    private static final ExternalApiCallCounter INSTANCE =
            new ExternalApiCallCounter(Clock.system(ZoneId.of("Asia/Seoul")));
    private final Clock clock;
    private final Map<String, Long> totals = new HashMap<>();
    private final Map<String, Long> daily = new HashMap<>();
    private LocalDate date;
    private final Map<ApiQuota, Long> limits = new java.util.EnumMap<>(ApiQuota.class);

    public static void configureLimits(java.util.function.Function<String, Long> settings) {
        synchronized (INSTANCE) {
            INSTANCE.limits.clear();
            for (ApiQuota quota : ApiQuota.values()) {
                Long limit = settings.apply(quota.name());
                if (limit != null) {
                    if (limit < 0) throw new IllegalArgumentException("Daily quota must not be negative: " + quota);
                    INSTANCE.limits.put(quota, limit);
                }
            }
        }
    }
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    public static void runScheduler(String name, Runnable task) {
        Run previous = CURRENT.get();
        Run run = new Run();
        CURRENT.set(run);
        boolean completed = false;
        try {
            task.run();
            completed = true;
        } finally {
            INSTANCE.summarize(name, run, completed);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void note(String event) {
        Run run = CURRENT.get();
        if (run != null) run.events.merge(event, 1L, Long::sum);
    }

    private static final class Run {
        final Map<ApiQuota, Long> quotas = new java.util.EnumMap<>(ApiQuota.class);
        final Map<String, Long> events = new java.util.TreeMap<>();
    }

    private synchronized void summarize(String name, Run run, boolean completed) {
        LocalDate today = LocalDate.now(clock);
        StringBuilder message = new StringBuilder("========== [스케줄러 호출량] ")
                .append(name).append(completed ? " 실행 종료" : " 예외로 중단")
                .append(" | ").append(today).append(" (KST) ==========");
        message.append("\n  --- 일일 한도별 사용률 (참고값은 실제 승인 한도 확인 필요) ---");
        for (ApiQuota quota : ApiQuota.values()) {
            if (quota == ApiQuota.UNKNOWN || quota == ApiQuota.FCM || quota == ApiQuota.SMTP) continue;
            long used = today.equals(date) ? daily.getOrDefault("quota:" + quota.name(), 0L) : 0L;
            message.append("\n  ").append(quota).append(" | 이번 실행 ")
                    .append(run.quotas.getOrDefault(quota, 0L)).append("회 | 오늘 ")
                    .append(used).append("회 | ").append(quota.usage(used, limits.get(quota)));
        }
        message.append("\n  처리 상태: ").append(run.events.isEmpty() ? "별도 상태 없음" : run.events);
        log.info("{}", message);
    }

    ExternalApiCallCounter(Clock clock) {
        this.clock = clock;
    }

    public static void record(String provider, String api) {
        INSTANCE.recordAttempt(provider, api);
    }

    synchronized Counts recordAttempt(String provider, String api) {
        ApiQuota quota = ApiQuota.of(provider, api);
        Run run = CURRENT.get();
        if (run != null) run.quotas.merge(quota, 1L, Long::sum);
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(date)) {
            daily.clear();
            date = today;
        }
        String providerKey = "provider:" + provider;
        long quotaUsed = daily.merge("quota:" + quota.name(), 1L, Long::sum);
        String apiKey = "api:" + provider + ":" + api;
        Counts counts = new Counts(
                totals.merge(providerKey, 1L, Long::sum), daily.merge(providerKey, 1L, Long::sum),
                totals.merge(apiKey, 1L, Long::sum), daily.merge(apiKey, 1L, Long::sum));
        log.info("========== [API 호출량] {} | 오늘 {}회 / 누적 {}회 | API={} 오늘 {}회 / 누적 {}회 | 한도그룹={} 오늘 {}회 | {} | {} (KST) ==========",
                provider, counts.providerToday(), counts.providerTotal(), api, counts.apiToday(), counts.apiTotal(),
                quota, quotaUsed, quota.usage(quotaUsed, limits.get(quota)), today);
        return counts;
    }

    record Counts(long providerTotal, long providerToday, long apiTotal, long apiToday) {}
}
