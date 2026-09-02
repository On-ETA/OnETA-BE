package com.OnETA.controller;

import com.OnETA.service.SeoulBusRouteSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TestSyncController {

    private final SeoulBusRouteSyncService syncService;

    @GetMapping("api/test/sync-bus")
    public String triggerSync() {
        // 기존에 작성한 스케줄러 메서드를 직접 호출합니다.
        syncService.syncSeoulBusRoutes();
        return "수동 동기화가 백그라운드에서 완료되었습니다. 콘솔 로그와 DB를 확인하세요.";
    }
}