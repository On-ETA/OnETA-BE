# 차고지/회차지 출발 알림 (Depot Notification)

이 문서는 사용자가 등록한 버스가 차고지나 회차지에서 출발할 때 실시간으로 감지하여 푸시 알림을 보내는 **출고지/회차지 알림** 기능의 전체 흐름과 구현 로직을 설명합니다.

## 1. 목적

특정 정류장에 도착하는 시간을 기준으로 하는 `ArrivalNotification`과 달리, 사용자가 등록해둔 특정 노선의 버스가 차고지 또는 회차지를 막 출발했을 때를 실시간으로 감지하여 알림을 제공합니다. 이는 배차 간격이 길거나 출퇴근 시간대에 기점에서 출발하는 버스를 타야 하는 사용자에게 유용합니다.

## 2. 전체 흐름

```text
사용자 알림 등록 (최대 5개)
      |
      v
UserBus + DepotNotification(active=true) 저장
      |
      | 30초 주기 (fixedDelay = 30000)
      v
활성 알림(active=true)이 존재하는 고유 노선(routeId)만 조회
      |
      v
공공 API 실시간 버스 위치 폴링 (SeoulBusLocationService)
      |
      v
차고지/회차지 출발 이벤트 감지 (isDepotDeparted / isTurnaroundDeparted)
      |
      v
조건에 맞는(routeId, 방향) 활성화된 알림 일괄 조회
      |
      v
FCM 푸시 발송 및 즉시 비활성화 (active=false)
```

## 3. 알림 데이터 모델

알림 상태 관리를 위해 두 가지 엔티티를 활용합니다.

- **`UserBus`**: 사용자와 버스 노선을 매핑합니다. 노선 ID, 버스 번호, 방면(Direction) 정보를 담고 있으며 인당 **최대 5개**까지만 등록할 수 있습니다.
- **`DepotNotification`**: `UserBus`와 1:1로 매핑되는 엔티티입니다. 알림의 활성화 여부(`active`)를 관리합니다.

알림을 삭제할 때는 `DepotNotification`을 삭제하는 대신 `UserBus`를 삭제하며, JPA의 `CascadeType.ALL` 설정에 의해 `DepotNotification`도 연쇄적으로 자동 삭제됩니다.

## 4. 실시간 폴링 스케줄러

폴링 스케줄러는 `BusLocationPollingService.pollBusLocations()`에서 담당하며, 매 30초(`fixedDelay = 30000`)마다 실행됩니다.

### 4.1 타겟 노선 필터링
전체 노선을 폴링하면 공공 API 트래픽 한도를 초과할 수 있으므로, 현재 알림이 켜져 있는(`active=true`) 고유 노선(`routeId`) 목록만 `findDistinctActiveRouteIds()`로 추출하여 폴링 대상 개수를 최소화합니다.

### 4.2 API 호출 및 레이트 리밋 방어
필터링된 활성 노선들에 대해 `seoulBusLocationService.getRealTimeBusLocations(routeId)`를 호출해 실시간 버스 위치 데이터를 파싱합니다. 각 노선 API 호출 사이에는 공공 데이터 API의 호출 제한을 방어하기 위해 `Thread.sleep(500)` 지연이 포함되어 있습니다.

### 4.3 출발 감지 및 트리거
API 파싱 결과에서 출발 상태를 감지합니다.
- `locationInfo.isDepotDeparted()`: 차고지 출발이 감지되면 회차지 방면(`BusDirection.TURNAROUND`)으로 알림을 트리거.
- `locationInfo.isTurnaroundDeparted()`: 회차지 출발이 감지되면 차고지 방면(`BusDirection.DEPOT`)으로 알림을 트리거.

## 5. 발송 처리 및 1회성 정책

출발 이벤트가 트리거되면 `DepotNotificationService.triggerDepotDeparture()`가 실행됩니다.

1. **대상자 조회**: 감지된 노선(`routeId`)과 방향(`BusDirection`)을 가진 사용자 중 알림이 켜진(`active=true`) 모든 `DepotNotification`을 조회합니다.
2. **푸시 발송**: 사용자 이메일, 버스 번호, 방면 정보를 조합하여 FCM 메시지를 구성하고 `fcmService.sendPush()`를 통해 발송합니다.
3. **단건(1회성) 처리**: 발송 직후 개별 알림 엔티티의 `disableNotification()`을 호출해 상태를 `active=false`로 바꿉니다.

즉, 이 알림 기능은 매일 반복되는 것이 아니라, 한 번 발송되면 종료되는 **1회성 알림**으로 동작합니다. 다시 알림을 받으려면 사용자가 기능을 다시 활성화해야 합니다.

## 6. 특징 및 한계점

- **Durable Outbox 부재**: `ArrivalNotification`과 달리 `DepotNotification`은 `notification_deliveries` 같은 별도의 Outbox 테이블에 발송 작업을 저장하거나 재시도(Retry) 정책을 적용하지 않습니다. FCM 발송 중 예외가 발생하더라도 재시도 없이 흐름이 끝날 수 있습니다.
- **방향 매핑 한계**: 실시간 데이터의 출발지(차고지/회차지)에 따라 일괄적으로 반대 방면 방향을 향하는 것으로 간주합니다. 공공 데이터의 특성상 세밀한 구간별 매핑보다는 기·종점 기반의 출발 이벤트에 의존합니다.
- **단일 서버 스케줄러 의존**: 스케줄러가 여러 서버에서 동시에 실행될 경우, 동일한 노선에 대해 중복 폴링 및 중복 알림 발송 문제가 발생할 수 있습니다. (현재 분산 락 처리는 구현되어 있지 않습니다.)
