package com.OnETA.common.exception;

import com.OnETA.controller.TransitController;
import com.OnETA.service.TransitApiService;
import com.OnETA.service.TransitRouteOptimizationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new TransitController(mock(TransitApiService.class),
                            mock(TransitRouteOptimizationService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void firstLastSearchReportsMissingOriginXWithC006() throws Exception {
        mvc.perform(get("/api/transit/routes/first-last/search")
                        .param("originAddress", "서울 마포구 와우산로 94")
                        .param("destAddress", "서울 구로구 공원로6길 25")
                        .param("scheduleType", "LAST_TRANSIT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C006"))
                .andExpect(jsonPath("$.message").value(containsString("originX")));
    }

    @Test
    void firstLastSearchReportsMissingOriginYWithC006() throws Exception {
        mvc.perform(get("/api/transit/routes/first-last/search")
                        .param("originX", "126.9246")
                        .param("originAddress", "서울 마포구 와우산로 94")
                        .param("scheduleType", "FIRST_TRANSIT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C006"))
                .andExpect(jsonPath("$.message").value(containsString("originY")));
    }

    @Test
    void firstLastSearchReportsMissingScheduleTypeWithC006() throws Exception {
        mvc.perform(get("/api/transit/routes/first-last/search")
                        .param("originX", "126.9246")
                        .param("originY", "37.5501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C006"))
                .andExpect(jsonPath("$.message").value(containsString("scheduleType")));
    }

    @Test
    void ordinaryTransitSearchAlsoNamesTheMissingParameter() throws Exception {
        mvc.perform(get("/api/transit/routes/search")
                        .param("originY", "37.5501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C006"))
                .andExpect(jsonPath("$.message").value(containsString("originX")));
    }
}
