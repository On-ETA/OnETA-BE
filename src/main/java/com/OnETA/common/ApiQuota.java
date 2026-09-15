package com.OnETA.common;

import java.util.Locale;

enum ApiQuota {
    ODSAY(1000), KAKAO(100000), SEOUL_ARRIVAL(1000), SEOUL_LOCATION(1000),
    SEOUL_ROUTES(1000), TAGO_ARRIVAL(10000), TAGO_STATIONS(10000), FCM(0), SMTP(0), UNKNOWN(0);

    final long referenceLimit;
    ApiQuota(long referenceLimit) { this.referenceLimit = referenceLimit; }

    static ApiQuota of(String provider, String api) {
        return switch (provider) {
            case "ODSAY" -> ODSAY;
            case "KAKAO" -> KAKAO;
            case "SEOUL_BUS" -> switch (api) {
                case "arrival" -> SEOUL_ARRIVAL;
                case "location" -> SEOUL_LOCATION;
                case "routes", "route-stations" -> SEOUL_ROUTES;
                default -> UNKNOWN;
            };
            case "TAGO" -> api.contains("ArvlInfoInqireService") ? TAGO_ARRIVAL
                    : api.contains("BusSttnInfoInqireService") ? TAGO_STATIONS : UNKNOWN;
            case "FCM" -> FCM;
            case "SMTP" -> SMTP;
            default -> UNKNOWN;
        };
    }

    String usage(long used, Long configuredLimit) {
        if (this == FCM) return "일일 한도/사용률 해당 없음 (분당 쿼터 별도)";
        long limit = configuredLimit == null ? referenceLimit : configuredLimit;
        if (limit <= 0) return "일일 한도 미설정 | 사용률 계산 불가";
        return String.format(Locale.ROOT, "일일 %s %,d회 | 사용률 %.2f%% (서버 집계)",
                configuredLimit == null ? "참고 한도" : "설정 한도", limit, used * 100.0 / limit);
    }
}
