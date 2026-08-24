# Notification System

이 문서는 현재 코드에 구현된 출발 알림(`ArrivalNotification`)의 후보 계산, Durable Outbox, FCM 발송 및 재시도 정책을 설명한다. 문서에 적힌 동작은 `NotificationScheduler`, `NotificationDeliveryService`, `FcmPushService`, `NotificationDelivery`와 관련 Repository/마이그레이션을 기준으로 한다.

## 1. Overview

현재 흐름은 다음과 같다.

```text
활성 ArrivalNotification 전체 조회
  → 후보 시간 사전 판단
  → 조건을 만족하면 Transit API로 실시간 소요 시간 조회
  → 최종 후보 계산
  → notification_deliveries에 PENDING Delivery 생성
  → claim (PESSIMISTIC_WRITE)
  → SENDING 상태를 commit
  → FCM 발송
  → 성공하면 SENT, 영구 실패하면 FAILED,
    deadline 소진이면 EXPIRED, 일시 실패면 retry
```

스케줄러는 매분 후보를 계산하고, 같은 실행의 마지막에 `processPending()`을 호출한다. 재시도는 메모리의 `ScheduledExecutorService`로 빠르게 실행하며, 매분 실행되는 `processPending()`이 DB에 남은 PENDING 또는 오래된 SENDING 작업을 다시 찾아 recovery한다. 따라서 FCM 호출 전에 발송 작업을 DB에 기록하는 Outbox가 발송 처리의 내구성 기준이다.

## 2. Notification Scheduling

`HomeRunApplication`의 `@EnableScheduling`으로 Spring Scheduling이 활성화되어 있다. `NotificationScheduler.scheduleArrivalNotifications()`의 실행 주기는 다음과 같다.

- Scheduler cron: `0 * * * * *` — 매분 0초
- cron timezone: `${app.time-zone:Asia/Seoul}`
- 후보 계산 timezone: `${app.time-zone:Asia/Seoul}`. 설정이 없으면 `Asia/Seoul`
- 현재 저장소 설정에는 `app.time-zone`의 별도 값이 없으므로 기본값이 적용된다.

스케줄러는 `arrivalNotificationRepository.findAllByIsActiveTrue()`로 활성 `ArrivalNotification` 전체를 조회한다. 현재 DB에서 후보를 좁히는 window query는 없으며, 전체 조회 구조가 유지된다.

각 알림에 대해 다음 순서로 판단한다.

1. `today == lastSentDate`이면 건너뛴다.
2. `repeatDays == null || repeatDays == 0`이면 단건 알림으로 본다. 그 외에는 오늘의 요일이 반복 요일 mask에 포함되어야 한다.
3. Transit API를 호출하기 전에 `오늘의 targetArrivalTime - reminderOffsetMinutes`를 기본 후보로 계산한다. 이 기본 후보가 `now - 1분`보다 과거이면 Transit API를 호출하지 않는다.
4. 사전 필터를 통과한 경우에만 `transitApiService.getRealTimeDuration(routeDetails)`를 호출한다.
5. 최종 후보는 `목표 도착 날짜·시각 - 실시간 소요 시간 - reminderOffsetMinutes`로 계산한다. 단건 알림은 오늘부터 내일까지, 반복 알림은 오늘부터 최대 7일까지 후보 날짜를 순회하지만, 실제 준비 단계는 최종 후보가 오늘 날짜이고 후보가 현재 시각까지 도달한 경우에만 진행한다.
6. 후보가 현재보다 미래이면 아직 발송하지 않는다. 현재보다 최대 1분 지난 후보는 스케줄러 지연을 허용하는 window 안에서 발송 대상이 될 수 있다.

최종 후보가 오늘 날짜이고 `candidateTime <= now`이며 `candidateTime >= now - 1분`이면 `scheduledAt`을 계산한다. 이 값은 후보 시각을 해당 업무 timezone에 붙인 뒤 UTC와 같은 순간으로 변환한 `LocalDateTime`이다. 이후 `prepare()`가 이 UTC 기준 값을 Delivery에 저장한다.

단건 알림은 성공, 영구 실패 또는 deadline 소진 시 `isActive=false`가 된다. 반복 알림은 성공 시 `lastSentDate`를 갱신하고 계속 활성 상태로 남는다.

## 3. Durable Outbox

최종 후보가 준비되면 `NotificationDeliveryService.prepare()`가 사용자의 현재 디바이스 토큰을 조회하고 `notification_deliveries`에 `PENDING` Delivery를 생성한다. 토큰이 없으면 Delivery를 생성하지 않는다. 생성 시 발송 시점의 token, title, body, `scheduledAt`, `hardDeadlineAt`을 Delivery에 고정한다.

`(notification_id, delivery_date)`에는 `uk_notification_delivery_date` UNIQUE 제약이 있다. 매분 같은 후보를 다시 계산하거나 여러 실행이 겹쳐도 동일 알림의 같은 날짜 Delivery가 중복 생성되지 않도록 하는 DB 수준의 최종 방어선이다.

Delivery 상태의 의미는 다음과 같다.

| 상태 | 의미 |
|---|---|
| `PENDING` | 아직 FCM 호출을 하지 않았거나, transient failure 후 다음 시도를 기다리는 상태 |
| `SENDING` | 한 실행이 claim하고 FCM 호출을 진행 중인 상태 |
| `SENT` | FCM `send()`가 성공적으로 반환되어 발송 완료를 기록한 상태 |
| `FAILED` | 영구적인 FCM/설정/토큰 오류로 더 이상 재시도하지 않는 상태 |
| `EXPIRED` | `hardDeadlineAt`에 도달했거나 다음 재시도가 deadline 안에 끝날 수 없어 포기한 상태 |

claim은 짧은 DB transaction에서 `SENDING`으로 변경하고 `attempts`와 `lastAttemptAt`을 기록한 뒤 commit한다. FCM 호출은 그 transaction 밖에서 수행한다. 외부 네트워크 호출을 DB transaction 안에 넣으면 DB row lock과 transaction을 FCM 응답 시간만큼 유지하게 되므로, DB lock 경합과 connection 점유를 불필요하게 늘릴 수 있다. 현재 구조는 DB에는 “누가 처리 중인지”를 먼저 durable하게 남기고 외부 호출은 transaction 밖에서 실행한다.

## 4. Concurrency / Claim

`NotificationDeliveryRepository.findByIdForUpdate()`는 다음 Repository 선언을 사용한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select d from NotificationDelivery d where d.id = :id")
Optional<NotificationDelivery> findByIdForUpdate(Long id);
```

실제 흐름은 다음과 같다.

1. `processDelivery()`가 FCM 동시성 Semaphore를 확보한다.
2. 별도의 transaction에서 `findByIdForUpdate()`를 호출한다. DB에서는 `SELECT ... FOR UPDATE`에 해당하는 row lock이 걸린다.
3. 이미 `SENT`, `FAILED`, `EXPIRED`이면 claim하지 않는다.
4. deadline, `SENDING` timeout(5분), `PENDING.nextAttemptAt`을 검사한다.
5. 처리 가능하면 `markSending(now)`으로 상태와 시도 정보를 변경하고 transaction을 commit한다.
6. commit 후 row lock이 풀린 상태에서 FCM을 호출한다.

두 실행이 동시에 같은 Delivery를 처리해도 row lock을 획득한 한 쪽만 상태를 `SENDING`으로 바꾸고, 다른 쪽은 갱신된 상태/최근 시도 시각을 보고 claim하지 않는다. FCM 호출 동안 lock을 유지하지 않는 이유는 위와 같이 네트워크 지연 중 DB row를 장시간 잠그지 않기 위해서다. 그 대신 `SENDING` 상태와 5분 timeout을 recovery 기준으로 사용한다.

프로세스 내부에는 `maxConcurrentFcm=4` 기본 Semaphore가 있어 동시에 FCM을 호출하는 수를 제한한다. Semaphore를 얻지 못하면 DB lock으로 `LOCAL_CONCURRENCY_LIMIT` transient failure를 기록하고 기본 1초 뒤 재시도한다.

## 5. Deadline Policy

- `scheduledAt`: 후보 계산으로 결정한 알림 발송 예정 시각. 현재 구현에서는 UTC 기준 `LocalDateTime`으로 Delivery에 저장한다.
- `hardDeadlineAt`: 이 Delivery가 FCM 발송을 시도할 수 있는 최종 시각.
- `sentAt`: FCM `send()` 성공 후 `markSent()` transaction에서 기록하는 UTC 기준 `LocalDateTime`.

현재 `prepare()`의 계산은 다음과 같다.

```text
hardDeadlineAt = scheduledAt + reminderOffsetMinutes
```

따라서 후보 시각부터 사용자가 설정한 reminder offset만큼의 시간 동안 transient failure에 대한 재시도 기회를 유지한다. 신규 Delivery는 `scheduledAt`과 `hardDeadlineAt`을 반드시 저장한다. 기존 계약으로 생성된 Delivery는 V9 migration에서 제거되므로 deadline 없는 레코드는 남지 않는다.

개별 알림은 단순 시도 횟수 때문에 포기하지 않는다. 사용자가 설정한 미리 알림 시간이 소진되는 `hardDeadlineAt`까지 transient failure에 대한 재시도 기회를 유지한다. `attempts`는 포기 기준이 아니라 실제 FCM 호출 횟수를 관측하기 위한 값이다.

`now >= hardDeadlineAt`이면 FCM을 호출하지 않고 `EXPIRED`로 처리한다. `processPending()` 시작 시 만료 후보를 먼저 정리하고, claim 시점과 failure 처리 시점, 성공 후 `markSent()` 시점에도 deadline을 재확인한다. 다음 재시도 시간이 deadline 이상이면 그 재시도를 예약하지 않고 `EXPIRED`로 처리한다.

## 6. Retry Policy

Transient failure의 기본 지연은 다음 계산을 사용한다.

```text
base = 10초
지수 배수 = 2^(attempts - 1)       (내부 exponent 상한 적용)
backoff = min(base × 지수 배수, 1분)
delay = backoff + [0, backoff × 0.2] 범위의 random jitter
```

현재 기본 설정은 `exponential-base-delay=10s`, `max-backoff=1m`, `jitter-ratio=0.2`이다. FCM 응답의 `Retry-After` header를 파싱할 수 있으면 이 값이 기본 exponential backoff보다 우선한다. `Retry-After`는 초 단위 또는 RFC 1123 HTTP-date 형식을 지원한다. `QUOTA_EXCEEDED`이면서 Retry-After가 없으면 `quota-fallback-delay=1분`을 사용한다.

재시도 시각은 `nextAttemptAt`에 저장한다. 즉시 실행 경로는 `ScheduledExecutorService` 기반 4개 thread pool에 Delivery별 task를 등록한다. 같은 Delivery에 이미 실행 중인 retry task가 있으면 중복 task를 등록하지 않는다. 동시에 매분 `processPending()`이 최대 100건(`BATCH_SIZE=100`)을 조회해 DB에 남은 작업을 recovery한다. 조회 대상은 `PENDING`이고 `nextAttemptAt <= now`인 작업, 또는 마지막 시도가 5분 이상 지난 `SENDING` 작업이다. deadline이 지난 작업은 먼저 `EXPIRED` 처리한다.

오류별 현재 정책은 다음과 같다.

| FCM 오류/상황 | 현재 처리 |
|---|---|
| `UNREGISTERED` | `FAILED`; 해당 user의 현재 token이 Delivery token과 일치할 때만 token 삭제 |
| token 오류로 판정된 `INVALID_ARGUMENT` | `FAILED`; 위와 같은 조건으로 token 삭제 |
| token 여부가 명확하지 않은 `INVALID_ARGUMENT` | Firebase Admin SDK가 구조적으로 구분하지 못하므로 현재 구현의 `isTokenError(message)`가 token 문구를 확인하지 못하면 retryable로 남을 수 있음 |
| `SENDER_ID_MISMATCH` | `FAILED`; token 유지 |
| `THIRD_PARTY_AUTH_ERROR` | `FAILED`; token 유지 |
| `UNAVAILABLE` | transient failure; exponential backoff + jitter로 retry |
| `INTERNAL` | 영구 오류로 분류되지 않으므로 transient failure; exponential backoff + jitter로 retry |
| `QUOTA_EXCEEDED` | Retry-After가 있으면 그 값, 없으면 1분 fallback으로 retry |
| `DELIVERY_EXPIRED` 또는 deadline 도달 | FCM을 더 호출하지 않고 `EXPIRED` |
| 그 밖의 일반 예외 | `FcmPushException`의 non-permanent 오류로 변환되어 deadline까지 retry |

`UNREGISTERED`와 token으로 명확히 판정된 `INVALID_ARGUMENT`만 token 삭제 대상이다. 설정 오류인 `SENDER_ID_MISMATCH`, `THIRD_PARTY_AUTH_ERROR`는 token 자체가 잘못되었다고 단정하지 않으므로 유지한다.

## 7. FCM TTL / Expiration

`FcmPushService`는 `hardDeadlineAt`이 있는 메시지에 Android와 iOS 설정을 함께 넣는다.

- Android: `AndroidConfig.ttl`에 `현재 시각부터 hardDeadlineAt까지 남은 milliseconds`를 설정한다.
- iOS/APNs: `ApnsConfig`의 `apns-expiration` header에 `hardDeadlineAt`의 UTC epoch seconds를 설정한다.

FCM 호출 직전에 남은 시간이 0 이하이면 `DELIVERY_EXPIRED`를 발생시켜 발송하지 않는다. TTL/expiration의 목적은 FCM이 이미 접수한 메시지가 `hardDeadlineAt` 이후 사용자에게 뒤늦게 전달되는 가능성을 줄이는 것이다. 이는 백엔드의 deadline 정책과 FCM이 보관하는 메시지의 수명을 연결하는 장치이지, 실제 기기 표시 시각을 보장하는 기능은 아니다.

## 8. Time Policy

현재 시간 정책은 업무 시간 계산과 발송 처리 시간을 분리한다.

| 항목 | 현재 기준 |
|---|---|
| 업무/후보 계산 timezone | `app.time-zone`, 기본 `Asia/Seoul`; `ZonedDateTime.now(clock.withZone(zoneId))`로 오늘/요일/목표 시각 계산 |
| Scheduler cron | `@Scheduled`의 `zone`에 같은 `app.time-zone` 기본값 사용 |
| Delivery 처리용 Clock | `Clock.systemUTC()` |
| FCM TTL 계산용 Clock | `Clock.systemUTC()` |
| DB timestamp | `LocalDateTime`으로 저장되는 `DATETIME`; Delivery의 처리 timestamp는 UTC 기준 값 |
| `scheduledAt` | 업무 timezone에서 계산한 후보 순간을 UTC로 변환해 저장 |
| `hardDeadlineAt` | UTC 기준 `scheduledAt + reminderOffsetMinutes` |
| `sentAt` | UTC Clock으로 FCM 성공 직후 기록 |
| `nextAttemptAt` | UTC Clock으로 계산한 retry 시각 |

후보 계산에서 `LocalDate`와 `LocalDateTime`은 `app.time-zone`의 업무 의미를 위해 사용하고, Delivery/FCM 처리의 `now`는 UTC instant를 `LocalDateTime`으로 표현한다. DB `DATETIME` 자체에는 timezone 정보가 없으므로, Delivery의 timestamp 필드는 UTC 값이라는 전제를 유지해야 한다.

## 9. Delivery Metrics

- `attempts`: `markSending()`이 호출될 때 1 증가한다. 실제 FCM 호출을 시도한 횟수이며, transient failure 이후에도 누적된다. retry를 중단할 MAX_ATTEMPTS 값으로 사용하지 않는다.
- `scheduledAt`: 후보 계산으로 결정한 예정 발송 시각이다.
- `sentAt`: `FirebaseMessaging.getInstance().send(message)`가 성공적으로 반환된 뒤 DB에 기록되는 시각이다. 실제 사용자 기기에 표시된 시각이 아니라 FCM acceptance 시각이다.
- `deliveryLatency`: `scheduledAt`과 `sentAt`이 모두 있을 때 `sentAt - scheduledAt`으로 계산되는 `Duration`이다.

따라서 `deliveryLatency`는 실제 기기 수신 latency가 아니라 `scheduled time → FCM acceptance latency`이다. `scheduledAt` 또는 `sentAt`이 없으면 null이다.

## 10. Known Limitations

### 10.1 FCM 성공 후 SENT 저장 전 장애 시 중복 발송 가능

현재 흐름은 다음과 같다.

```text
FCM 성공
  → SENT DB 저장
```

FCM 성공 후 `SENT` 저장 전에 프로세스가 종료되면 FCM에는 메시지가 접수됐지만 DB에는 `SENDING` 상태가 남을 수 있다. 이후 매분 recovery가 5분 이상 지난 `SENDING`을 다시 처리할 수 있으므로 중복 발송 가능성이 있다.

Durable Outbox, UNIQUE 제약, DB claim은 일반적인 중복을 방지하지만 외부 FCM 호출과 DB transaction을 하나의 원자적 transaction으로 묶을 수 없다. 따라서 exactly-once delivery는 완전히 보장하지 않는다. 현재는 Known Limitation으로 유지한다.

### 10.2 실제 사용자 기기 표시 시각 측정 불가

`sentAt`은 FCM acceptance 시각이다. 사용자 기기가 실제로 언제 메시지를 수신하고 notification UI를 표시했는지는 현재 백엔드 구조만으로 측정하지 않는다.

TTL/expiration으로 오래된 메시지가 뒤늦게 전달될 가능성은 줄이고 있지만, 실제 표시 시각을 보장하거나 측정하는 기능은 아니다. 현재는 Known Limitation으로 유지한다.

## 11. Future Improvements

이 절의 항목은 현재 구현 대상이 아니다. 다음 작업자나 AI 코딩 도구는 Future Improvement라는 이유만으로 코드를 추가하거나 구조를 변경하지 않는다.

### 운영 데이터가 쌓이면 검토

- `EXPIRED` 발생 건수/rate 모니터링
- `deliveryLatency` 평균/P95/P99 등 확인
- `attempts` 분포 확인

특히 `EXPIRED`는 “사용자가 설정한 미리 알림 시간이 모두 소진될 때까지 FCM 발송에 성공하지 못한 상태”이므로 중요한 서비스 품질 지표다.

### 서비스 규모 증가 시 검토

- 전역 FCM rate limiter
- 다중 서버 환경의 distributed concurrency control
- 대량 retry task가 `ScheduledExecutorService`에 미치는 영향
- `processPending` batch size 및 Repository index/EXPLAIN
- 다중 서버 outbox 처리 전략

현재 단일 인스턴스/현재 서비스 규모에서 필요하다고 확인되지 않았으므로 구현하지 않는다.

### `nextEvaluationAt`

현재는 매분 활성 Notification을 조회하고 Java에서 후보를 판단하는 구조를 유지한다. 활성 Notification 수 증가로 매분 전체 조회 비용이 커지거나, Transit API 평가 비용이 실제 병목으로 확인될 때에만 `nextEvaluationAt` 기반 평가 스케줄링을 검토한다.

`nextEvaluationAt`은 단순 컬럼 추가가 아니다. Notification 생성/수정, 반복 알림, 후보 조회, recovery 등 평가 구조 전반에 영향을 주는 변경이다. 따라서 현재 단계에서는 선제적으로 구현하지 않는다. 다른 개발자나 AI가 이 문서의 Future Improvement 항목을 현재 구현 지시로 해석해서는 안 된다.

## 12. Current Design Decisions

- Durable Outbox(`NotificationDelivery`)를 유지한다.
- `(notification_id, delivery_date)` UNIQUE 제약으로 동일 날짜 Delivery 중복 생성을 방지한다.
- Pessimistic Lock 기반 claim(`SELECT ... FOR UPDATE`)을 사용한다.
- FCM 호출은 DB transaction 밖에서 수행한다.
- FCM 호출 전에 `SENDING` 상태를 commit한다.
- transient failure은 MAX_ATTEMPTS로 조기 종료하지 않고 `hardDeadlineAt`까지 retry한다.
- `hardDeadlineAt`을 사용자 미리 알림의 최종 발송 시간 기준으로 사용한다.
- permanent failure은 `FAILED`, deadline 소진은 `EXPIRED`로 기록한다.
- `attempts`는 실제 FCM 호출 횟수 관측값이다.
- FCM Android TTL과 APNs expiration을 `hardDeadlineAt`에 연결한다.
- 메모리 `ScheduledExecutorService` retry와 매분 DB `processPending()` recovery를 병행한다.
- 현재는 활성 Notification 전체 조회 구조를 유지한다.
- `nextEvaluationAt`은 현재 도입하지 않는다.
