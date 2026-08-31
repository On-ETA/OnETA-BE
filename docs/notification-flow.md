# 알림 감지·스케줄러·발송 로직

이 문서는 현재 구현 기준으로 출발 알림이 생성된 뒤 스케줄러에 의해 감지되고, 후보 시각을 계산한 다음 FCM으로 발송되는 전체 흐름을 설명합니다.

## 1. 전체 흐름

```text
알림 생성/수정
      |
      v
notifications + arrival_notifications
      |
      | 매 분 실행
      v
활성 알림 전체 조회
      |
      v
요일·당일 발송 여부·후보 시간 사전 필터
      |
      v
Transit API로 실시간 소요 시간 조회
      |
      v
실시간 소요 시간을 반영해 최종 후보 계산
      |
      v
notification_deliveries에 발송 작업 저장
알림 상태(last_sent_date/is_active) 저장
      |
      | 위 DB 트랜잭션 커밋 후
      v
PENDING -> SENDING -> FCM 발송 -> SENT
```

핵심은 FCM을 호출하기 전에 발송 작업과 알림 상태를 DB에 저장한다는 점입니다. FCM 호출 중 장애가 발생해도 발송 작업이 남아 다음 스케줄 실행에서 재시도됩니다.

## 2. 알림 생성 시 저장되는 값

알림 생성 API는 `NotificationService.createArrivalNotification()`에서 처리합니다.

1. 사용자의 존재 여부와 목표 도착 시간, 알림 offset, 경로 정보를 검증합니다.
2. 반복 요일 문자열을 `RepeatDaysService.toMask()`로 정수 비트마스크로 변환합니다.
3. `ArrivalNotification`을 생성해 저장합니다.

공통 필드는 `notifications` 테이블에 저장됩니다.

- `notification_id`: 알림 식별자
- `user_id`: 알림 소유자
- `name`: 알림 이름 또는 경로 이름
- `reminder_offset_minutes`: 목표 도착 시간보다 몇 분 일찍 알릴지 나타내는 값
- `repeat_days`: 반복 요일 비트마스크
- `is_active`: 스케줄러 대상 여부
- `last_sent_date`: 마지막 발송 날짜

출발 알림 전용 값은 `arrival_notifications` 테이블에 저장됩니다.

- `target_arrival_time`: 목표 도착 시각
- `route_details`: 실시간 소요 시간 조회에 사용하는 경로 정보

`repeat_days == 0`이면 단건 알림으로 취급합니다. 0이 아닌 경우에는 선택된 요일에 반복되는 알림입니다.

## 3. 스케줄러 실행

스케줄링은 `HomeRunApplication`의 `@EnableScheduling`으로 활성화되어 있습니다.

`NotificationScheduler.scheduleArrivalNotifications()`는 다음 cron으로 실행됩니다.

```java
@Scheduled(cron = "0 * * * * *")
```

즉 매 분 0초를 기준으로 실행되도록 예약되어 있지만, 실제 애플리케이션 스레드가 정확히 0초에 작업을 시작한다는 보장은 없습니다. 따라서 현재 구현은 `MAX_CANDIDATE_DELAY`를 1분으로 두고 최대 1분의 실행 지연을 허용합니다.

현재 시각은 `app.time-zone`을 사용해 계산합니다. 설정이 없으면 기본값은 `Asia/Seoul`입니다.

주의할 점은 `@Scheduled`의 cron 자체에는 `zone` 속성이 지정되어 있지 않다는 것입니다. 따라서 cron 실행 기준 시간대는 JVM/서버 기본 시간대의 영향을 받을 수 있고, 후보 계산 시간대는 `app.time-zone`을 사용합니다. 운영 환경에서는 서버 기본 시간대와 `app.time-zone`을 동일하게 맞추는 것이 안전합니다.

## 4. 활성 알림 감지

스케줄러는 현재 다음 리포지터리 메서드로 활성 알림을 모두 조회합니다.

```java
arrivalNotificationRepository.findAllByIsActiveTrue()
```

아직 별도의 DB 조건으로 후보를 좁히지 않고 활성 출발 알림 전체를 메모리로 가져옵니다.

각 알림은 다음 순서로 빠르게 제외됩니다.

### 4.1 당일 중복 발송 확인

```java
if (today.equals(notification.getLastSentDate())) continue;
```

이미 오늘 발송한 알림은 반복 알림인지 단건 알림인지와 관계없이 즉시 건너뜁니다. 이 검사는 outbox가 이미 발송 작업을 생성한 뒤 다음 스케줄 실행에서도 같은 알림이 다시 준비되지 않도록 하는 1차 방어선입니다.

### 4.2 반복 요일 확인

단건 알림은 요일을 확인하지 않습니다. 반복 알림은 현재 날짜의 요일이 `repeatDays` 비트마스크에 포함되어 있어야 합니다.

선택된 요일이 아니면 Transit API를 호출하지 않고 바로 건너뜁니다.

### 4.3 Transit API 호출 전 사전 시간 필터

실시간 소요 시간을 조회하기 전, 현재 날짜의 기본 후보 시간을 계산합니다.

```text
기본 후보 시각 = 오늘의 목표 도착 시각 - reminder_offset_minutes
```

기본 후보가 현재 시각보다 1분 이상 과거이면 Transit API를 호출하지 않습니다. 이 단계는 불필요한 실시간 교통 API 호출을 줄이기 위한 최적화입니다.

반대로 후보가 현재 시각보다 미래이거나 최대 1분 이내로 지난 경우에는 다음 단계로 진행합니다.

## 5. 최종 후보 시각 계산

사전 필터를 통과하면 다음 순서로 실시간 소요 시간을 조회합니다.

```java
estimatedDuration = transitApiService.getRealTimeDuration(routeDetails)
```

그 뒤 `findNextCandidate()`가 후보 날짜를 순회합니다.

- 단건 알림: 오늘부터 내일까지 확인
- 반복 알림: 오늘부터 최대 7일 동안 선택된 요일 확인

각 후보의 발송 시각은 다음과 같이 계산됩니다.

```text
알림 발송 시각
= 목표 도착 날짜·시각
 - 실시간 예상 소요 시간
 - reminder_offset_minutes
```

예를 들어 목표 도착 시각이 18:30이고, 실시간 소요 시간이 30분이며, offset이 0분이면 후보 발송 시각은 18:00입니다.

## 6. 후보 시간 판정 정책

후보 탐색과 실제 발송 판정은 서로 다른 책임을 가집니다.

### 6.1 후보 탐색 범위

`isCandidateInWindow()`는 다음 조건을 사용합니다.

```text
candidateTime >= now - 1분
```

따라서 후보가 미래이거나 현재 시각보다 최대 1분 지난 경우 후보로 반환될 수 있습니다.

### 6.2 실제 발송 가능 여부

`isDueCandidate()`는 다음 두 조건을 모두 확인합니다.

```text
candidateTime <= now
candidateTime >= now - 1분
```

예시는 다음과 같습니다.

| 후보 시각 | 현재 시각 | 결과 |
|---|---|---|
| 18:00:00 | 18:00:00 | 발송 |
| 18:00:00 | 18:00:01 | 발송 |
| 18:00:00 | 18:01:00 | 발송 |
| 18:00:00 | 18:01:01 | 발송하지 않음 |
| 18:00:00 | 17:59:59 | 미래 후보이므로 발송하지 않음 |

`isTodayCandidate()`도 같은 1분 지연 정책을 사용합니다. 따라서 스케줄러가 18:00:01에 시작해도 18:00:00 후보가 사전 필터에서 탈락하지 않습니다.

## 7. Outbox 준비 단계

최종 후보가 오늘 날짜이고 발송 가능하면 `NotificationDeliveryService.prepare()`가 호출됩니다.

이 메서드는 하나의 트랜잭션에서 다음 작업을 수행합니다.

1. `notification_id + delivery_date` 조합으로 기존 발송 작업을 조회합니다.
2. 이미 작업이 있으면 새 작업을 만들지 않습니다.
3. 사용자의 디바이스 토큰을 조회합니다.
4. 토큰이 있으면 `notification_deliveries`에 `PENDING` 작업을 저장합니다.
5. 같은 트랜잭션에서 `last_sent_date`를 오늘 날짜로 변경합니다.
6. 단건 알림이면 `is_active=false`로 변경합니다.
7. 알림 상태 변경을 저장하고 트랜잭션을 커밋합니다.

디바이스 토큰이 없으면 outbox와 알림 상태를 만들지 않습니다.

`notification_deliveries`의 주요 필드는 다음과 같습니다.

- `notification_id`: 원본 알림
- `delivery_date`: 중복 방지 기준 날짜
- `device_token`: 발송 대상 토큰
- `title`, `body`: 발송 당시 확정된 메시지
- `status`: `PENDING`, `SENDING`, `SENT`
- `attempts`: 발송 시도 횟수
- `last_attempt_at`: 마지막 발송 시도 시각

`notification_id`와 `delivery_date`에는 유니크 제약이 있어 같은 알림이 같은 날 여러 outbox 작업으로 생성되지 않도록 합니다.

## 8. FCM 발송과 재시도

모든 활성 알림의 준비 단계가 끝나면 스케줄러는 `processPending()`을 호출합니다.

처리 대상은 `PENDING` 또는 `SENDING` 상태의 outbox입니다.

### 8.1 작업 선점

작업을 FCM에 보내기 전에 별도 트랜잭션으로 `SENDING` 상태로 변경하고 `attempts`, `last_attempt_at`을 갱신합니다.

이미 `SENT`인 작업은 처리하지 않습니다. `SENDING` 상태가 된 지 5분 이내인 작업도 다른 실행이 즉시 다시 처리하지 않습니다.

### 8.2 FCM 요청

선점 트랜잭션이 커밋된 뒤 `FcmPushService.sendPushMessage()`가 호출됩니다.

FCM 서비스는 다음 순서로 동작합니다.

1. Firebase 서비스 계정이 초기화되어 있는지 확인합니다.
2. 토큰, 제목, 본문으로 Firebase `Message`를 구성합니다.
3. Firebase Messaging API에 전송합니다.
4. 성공 시 Firebase message ID를 로그에 남깁니다.

### 8.3 성공

FCM 요청이 성공하면 별도 트랜잭션으로 outbox 상태를 `SENT`로 변경합니다.

### 8.4 실패

FCM 호출 또는 상태 저장 중 예외가 발생하면 outbox가 `PENDING`으로 돌아가고 로그가 기록됩니다. 다음 스케줄 실행에서 다시 조회되어 재시도됩니다.

## 9. 단건 알림과 반복 알림의 차이

### 단건 알림

- `repeatDays == 0`
- 후보 날짜는 오늘 또는 내일
- 발송 준비 시 `lastSentDate=오늘`로 저장
- 발송 준비 시 `isActive=false`로 변경
- 이후 활성 알림 조회 대상에서 제외됨
- 이미 생성된 outbox는 활성 알림 조회와 별개로 `processPending()`에서 계속 처리됨

### 반복 알림

- `repeatDays != 0`
- 선택된 요일에만 후보 생성
- 발송 준비 시 `lastSentDate=오늘`로 저장
- `isActive`는 true로 유지
- 같은 날에는 `lastSentDate` 검사로 재발송하지 않음
- 다음 선택 요일이 되면 다시 후보가 생성될 수 있음

## 10. 장애 및 중복 방지

현재 중복 방지는 여러 계층으로 구성됩니다.

1. 활성 알림 조회 후 `lastSentDate` 당일 검사
2. `notification_id + delivery_date` 유니크 제약
3. outbox의 `SENT` 상태 검사
4. `SENDING` 상태의 5분 재시도 지연
5. 단건 알림의 `isActive=false` 처리

단, FCM은 외부 시스템이고 현재 호출에 별도 idempotency key를 지원하지 않습니다. 따라서 FCM 요청이 성공한 직후 애플리케이션이 중단되어 `SENT` 저장이 실패하면, 5분 후 재시도 과정에서 외부 FCM이 중복 수신될 가능성은 완전히 제거할 수 없습니다. 이 문제를 exactly-once로 해결하려면 FCM 공급자 측 idempotency 지원 또는 발송 결과를 중계하는 별도 외부 시스템이 필요합니다.

## 11. 현재 테스트 범위

`NotificationSchedulerTest`에서 다음 후보 시간 정책을 검증합니다.

- 정확한 후보 시각 발송
- 후보 시각 1초 지연 발송
- 후보 시각 1분 지연 발송
- 1분을 초과한 지연 미발송
- 단건 알림의 다음 날짜 처리
- 반복 알림의 선택 요일 처리
- 같은 날 중복 발송 방지
- 단건 알림 종료 및 반복 알림 활성 상태 유지

`NotificationDeliveryServiceTest`에서는 `SENT` 상태의 delivery가 다시 처리되지 않아 FCM이 중복 호출되지 않는지 검증합니다.

## 12. 관련 코드 위치

- 스케줄러: `src/main/java/com/HomeRun/scheduler/NotificationScheduler.java`
- Outbox 처리: `src/main/java/com/HomeRun/service/NotificationDeliveryService.java`
- FCM 호출: `src/main/java/com/HomeRun/service/FcmPushService.java`
- 출발 알림 생성·수정: `src/main/java/com/HomeRun/service/NotificationService.java`
- 알림 공통 엔티티: `src/main/java/com/HomeRun/entity/Notification.java`
- 출발 알림 엔티티: `src/main/java/com/HomeRun/entity/ArrivalNotification.java`
- Outbox 엔티티: `src/main/java/com/HomeRun/entity/NotificationDelivery.java`
- 스케줄링 활성화: `src/main/java/com/HomeRun/HomeRunApplication.java`
