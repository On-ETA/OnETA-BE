# FIRST/LAST 대중교통 출발 알림

## 목적

`NORMAL`은 목표 도착시각에서 ODsay 예상 소요시간과 일부 버스 실시간 도착정보를 차감한다. `FIRST_TRANSIT`과 `LAST_TRANSIT`은 저장된 `routeDetails`의 대중교통 구간과 당일 운행정보로 출발시각을 추정한다. 세 타입 모두 개별 차량과 환승 성공을 보장하지 않는 `ESTIMATED` 수준이다.

## 계산

```text
FIRST candidate(i) = firstServiceTime(i) - prefixDuration(i)
FIRST baseDepartureAt = MAX(candidate(i))

LAST candidate(i) = lastServiceTime(i) - prefixDuration(i)
LAST baseDepartureAt = MIN(candidate(i))

baseScheduledAt = baseDepartureAt - reminderOffset
```

`prefixDuration`는 해당 transit 구간 이전의 `sectionTime` 합계이며 해당 구간 자체의 시간이나 `scheduledWaitMinutes`를 중복해서 더하지 않는다.

## 흐름

```text
FIRST/LAST
→ 당일 Base Schedule snapshot
→ FIRST는 평가 시작 이후 버스 safety 평가
→ scheduledAt 도달
→ BASE Delivery
```

FIRST에서 첫차 기회가 지나고 SENT가 없으면 다음 버스 arrival을 한 번 조회해 Recovery Delivery를 최대 하나 만든다.

```text
first opportunity expired
→ nextBoardingAt = now + arrivalSeconds
→ recoveryDepartureAt = nextBoardingAt - prefixDuration
→ recoveryScheduledAt = recoveryDepartureAt - recoveryReminderOffset
→ 이미 scheduledAt이면 즉시 RECOVERY Delivery
```

Recovery Delivery가 만들어진 뒤에는 다른 차량을 조회하지 않는다. FCM 실패는 기존 Delivery retry가 담당한다.
Recovery API timeout은 후보를 아직 확정하지 못한 경우에만 짧은 시간 재시도할 수 있으며, Delivery가 생성된 뒤에는 API를 다시 호출하지 않는다.

## Snapshot과 동시성

날짜·schedule type·route hash별 snapshot을 사용한다. Base/effective 시각, FIRST recovery 상태와 다음 API 재시도 시각을 저장한다. Recovery 생성은 snapshot lock, Delivery 상태 확인, `RECOVERY` phase unique constraint로 보호한다.

Delivery의 `scheduledAt`과 `hardDeadlineAt`은 생성 시 고정한다. BASE Delivery를 Recovery로 수정하지 않는다.

## Delivery

Delivery phase는 `BASE`와 `RECOVERY`이다. 기존 row는 `BASE`로 migration한다. Recovery Delivery의 deadline은 `nextBoardingAt`이다. SENDING 중인 BASE Delivery에서는 실제 FCM 결과를 알 수 없으므로 Recovery를 만들지 않는다.

## LAST 정책

현재 버스 실시간 API는 차량 ID나 막차 여부를 제공하지 않는다. 따라서 일반적인 다음 버스 arrival을 LAST 보정이나 Recovery 근거로 사용하지 않는다. LAST는 Base Schedule 중심이며, 막차 Recovery는 없다.

## 한계와 후속 과제

- 버스의 특정 차량·막차 여부·실제 trip은 식별하지 않는다.
- 지하철 실시간 arrival은 연동하지 않는다.
- 지하철-only FIRST Recovery는 지원하지 않는다.
- FIRST Recovery는 serviceDate당 최대 한 번이다.
- 전체 GTFS, EXACT 환승 검증, 반복 Recovery, `deliverySequence`는 범위에 포함하지 않는다. Recovery가 한 번으로 제한되므로 sequence를 두면 중복 Delivery 경로만 늘어나기 때문이다.
