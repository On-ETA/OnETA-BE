package com.OnETA.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiQuotaTest {
    @Test
    void computesPercentWithoutClampingAndAllowsUnknownLimits() {
        assertThat(ApiQuota.ODSAY.usage(120, 1000L)).contains("12.00%", "설정 한도");
        assertThat(ApiQuota.ODSAY.usage(1200, 1000L)).contains("120.00%");
        assertThat(ApiQuota.KAKAO.usage(0, null)).contains("0.00%", "참고 한도");
        assertThat(ApiQuota.SMTP.usage(12, null)).contains("계산 불가");
        assertThat(ApiQuota.ODSAY.usage(12, 0L)).contains("계산 불가");
        assertThat(ApiQuota.FCM.usage(12, null)).contains("분당").doesNotContain("%");
    }

    @Test
    void groupsSharedOperationsAndSeparatesDifferentServices() {
        assertThat(ApiQuota.of("SEOUL_BUS", "routes")).isEqualTo(ApiQuota.of("SEOUL_BUS", "route-stations"));
        assertThat(ApiQuota.of("SEOUL_BUS", "arrival")).isNotEqualTo(ApiQuota.of("SEOUL_BUS", "location"));
        assertThat(ApiQuota.of("TAGO", "/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"))
                .isEqualTo(ApiQuota.TAGO_ARRIVAL);
        assertThat(ApiQuota.of("TAGO", "/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList"))
                .isEqualTo(ApiQuota.TAGO_STATIONS);
        assertThat(ApiQuota.of("ODSAY", "/busStationInfo")).isEqualTo(ApiQuota.of("ODSAY", "searchPubTransPathR"));
    }
}
