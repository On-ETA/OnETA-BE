# 일정 목록과 단일 첫차·막차 알림

모든 API는 로그인 Bearer 토큰이 필요하다. 기존 `/api/notifications/arrival` 등록·전체 목록·상세·수정·삭제 API는 유지한다.

| 목적 | 등록 | 조회 |
| --- | --- | --- |
| 내 일정 | POST /api/notifications/schedules | GET /api/notifications/schedules |
| 첫차·막차 | POST /api/notifications/transit (기존 설정 교체) | GET /api/notifications/transit (단일 객체) |

## 내 일정

`scheduleType`은 NORMAL 또는 생략. `targetArrivalTime`은 필수다.

```json
{
  "routeName": "출근",
  "scheduleType": "NORMAL",
  "targetArrivalTime": "09:00:00",
  "reminderOffsetMinutes": [10],
  "repeatDays": ["MON", "TUE", "WED", "THU", "FRI"],
  "routeDetails": "검색 결과의 경로 후보 하나를 JSON.stringify한 문자열로 교체"
}
```

## 첫차·막차

첫차·막차는 **사용자당 두 종류를 합쳐 하나만 유지하는 일회성 알림**이다. 새 POST는 기존 알림을 보관 처리한 뒤 새 설정을 생성한다. 응답 ID는 교체할 때마다 달라지며, 기존 ID의 snapshot·발송 이력이 새 알림에 재사용되지 않는다. `scheduleType`은 FIRST_TRANSIT 또는 LAST_TRANSIT 필수다. 전용 등록 DTO에는 `routeName`, `targetArrivalTime`, `repeatDays`가 없다. 이전 앱이 이 값을 보내면 무시한다. 기존 `/arrival` 등록·수정에서도 첫차·막차는 목표 도착 시간 null, 반복 요일 0으로 저장한다. 일반 일정의 반복 기능은 유지한다.

```json
{
  "scheduleType": "LAST_TRANSIT",
  "reminderOffsetMinutes": [10],
  "routeDetails": "검색 결과의 경로 후보 하나를 JSON.stringify한 문자열로 교체"
}
```

두 예제의 routeDetails는 자리표시자다. 실제 검색 경로의 정류장 목록·좌표·provider를 보존해 넣어야 한다.

등록 응답은 기존과 같이 HTTP 200과 생성 ID다. 목록/상세 응답에는 `category: SCHEDULE | TRANSIT`와 `scheduleType: NORMAL | FIRST_TRANSIT | LAST_TRANSIT`가 함께 포함된다. 기존 `/arrival` 응답에서 첫·막차의 targetArrivalTime은 null이다. 전용 `/transit` 응답은 아래 예상 출발 시각 형식을 따른다. 내 일정은 목록으로, 첫차·막차는 본인의 현재 설정 하나만 반환한다. 보관된 알림은 제외한다.

첫차·막차 설정 변경은 새 본문으로 `POST /api/notifications/transit`을 호출한다. `DELETE /api/notifications/transit`은 ID나 목록 없이 현재 설정을 제거한다(보관 처리, 반복 호출 가능). 상태 변경은 기존 `/arrival/{id}/status`를 사용할 수 있으며 보관된 ID는 접근할 수 없다. 일반 일정의 기존 수정·삭제 API는 유지한다. 호환 `/arrival` 등록도 첫차·막차에 같은 교체 정책을 적용한다. NORMAL→첫·막차 PATCH는 다른 현재 첫·막차가 있으면 N006 제한 오류이며, 교체하려면 POST를 사용한다.

## 푸시 확인

1. 브라우저에서 온에타 알림 권한을 허용하고 로그인한다.
2. POST `/api/notifications/device-tokens`로 실제 FCM 토큰을 등록한다.
3. POST `/api/notifications/test`를 본인 로그인 토큰으로 호출한다. 요청 본문은 없다.
4. `data: FCM_ACCEPTED`는 Firebase 서버가 발송 요청을 수락했다는 의미다. 브라우저에 알림이 표시됐는지는 별도로 확인한다.

테스트 API는 다른 사용자/임의 토큰을 지정할 수 없으며 사용자별 1분 간격으로 제한한다. 등록 토큰이 없으면 400, Firebase 비활성화·발송 실패면 503 N004다. Firebase가 꺼진 상태를 발송 성공으로 처리하지 않는다.

서버 환경에 `APP_FIREBASE_ENABLED=true`, `FIREBASE_SERVICE_ACCOUNT=file:/절대경로/서비스계정.json`을 설정한다. Android `google-services.json`이나 웹 firebaseConfig는 서버 인증 파일이 아니다. 서비스 계정 파일과 application*.yml 변경은 커밋하지 않는다.

공식 문서: https://firebase.google.com/docs/admin/setup


## 첫차·막차 예상 출발 시각과 남은 시간

등록 응답은 HTTP 200과 새 ID를 반환한다. 등록 후 `GET /api/notifications/transit`으로 현재 설정을 조회한다.
응답 `data`는 배열이 아닌 아래 단일 객체다. 설정이 없으면 HTTP 200 `{ "code": "SUCCESS", "message": "요청이 성공적으로 처리되었습니다." }`를 반환한다(`data` 생략).
전용 조회 응답에는 `routeName`, `repeatDays`, `targetArrivalTime`이 없다. 기존 `/transit/{id}`는 현재 설정의 상세 조회 호환용으로 유지하며 보관된 ID는 404다. 기존 `/arrival` 조회 형식은 유지한다.

```json
{
  "category": "TRANSIT",
  "notificationId": 38,
  "scheduleType": "LAST_TRANSIT",
  "reminderOffsetMinutes": [10],
  "routeDetails": "저장된 경로 JSON 문자열",
  "isActive": true,
  "estimatedDepartureAt": "2026-09-28T00:10:00+09:00",
  "remainingSeconds": 1200,
  "serverTime": "2026-09-27T23:50:00+09:00",
  "estimateStatus": "ESTIMATED",
  "estimateErrorCode": null
}
```

- `estimatedDepartureAt`: 경로 출발 지점에서 출발할 예상 적정 시각. 정류장 탑승 시각이나 미리 알림 발송 시각이 아니다.
- `remainingSeconds`: `estimatedDepartureAt - serverTime`의 초 단위 값. 이미 지난 시각은 음수이며 0으로 제한하지 않는다.
- 날짜와 시간대를 함께 표시한다. 자정 이후 출발은 다음 날짜로 표현한다.
- 앱은 경로 이름·반복 요일·목표 도착 시간 입력과 첫차·막차 목록 처리를 제거하고 예상 출발 시각과 카운트다운을 표시한다. 매초 API를 호출할 필요는 없다. 응답의 남은 시간에서 앱에서 경과한 시간을 차감하고, 화면 재진입·새로고침 시 서버 값을 갱신한다. 전송 지연만큼의 오차가 있을 수 있다.
- 저장된 당일 계산 결과의 실시간 보정 시각을 우선 사용한다. 자정 이후 출발하는 전날 운행일 결과와 완료된 알림의 마지막 결과도 보존한다.
- 아직 계산 결과가 없으면 기존 경로·시간표 계산으로 미리 보여준다. 조회는 snapshot 저장, 실시간 차량 조회, Recovery 생성, 푸시 전송을 실행하지 않는다.
- 시간표를 확인할 수 없으면 `estimateStatus=UNAVAILABLE`, 시각·남은 시간은 null, `estimateErrorCode`에 T005/T006 등의 사유를 반환한다. 앱은 “출발 시각 확인 불가”로 표시한다. 저장된 설정 자체는 조회된다.
- V19 마이그레이션은 기존 첫차·막차의 `repeat_days`만 0으로 전환한다. 일반 일정의 요일과 기존 활성 상태는 유지한다.

## 기존 DB와 단일 알림 전환

- V20은 `arrival_notifications.transit_archived`를 기본값 false로 추가한다. 기존 Flyway 버전은 수정하지 않는다.
- 사용자별 첫차·막차 중 활성 알림의 최대 ID를 유지한다. 활성 알림이 없으면 전체 중 최대 ID를 유지한다. 나머지는 보관·비활성화한다.
- 보관된 알림의 PENDING/SENDING 발송은 EXPIRED로 전환한다. 알림 행, 기존 이름, 완료된 발송 및 snapshot 이력은 삭제하지 않는다.
- `notifications.name`은 일반 일정과 공용인 NOT NULL 컬럼이므로 유지한다. 새 첫차·막차는 내부 이름만 자동 지정하며, 전용 API에는 노출하지 않는다.
- 새 등록은 사용자 행 잠금 아래에서 검증→이전 알림 보관 및 대기 발송 만료→새 ID 생성 순서로 처리한다. 검증 실패 시 이전 설정을 유지한다.
- 이미 Firebase에 전달 중이거나 전달 완료된 푸시는 교체로 회수할 수 없다. 보관 상태는 새 발송을 획득할 때도 확인한다.
- 서버 코드와 V20을 함께 배포하고 구버전 서버의 쓰기를 중단한 뒤 적용한다. 일반 일정의 이름·반복 요일·5개 제한은 유지한다.
