# 일정·첫막차 등록과 목록

모든 API는 로그인 Bearer 토큰이 필요하다. 기존 `/api/notifications/arrival` 등록·전체 목록·상세·수정·삭제 API는 유지한다.

| 목적 | 등록 | 목록 |
| --- | --- | --- |
| 내 일정 | POST /api/notifications/schedules | GET /api/notifications/schedules |
| 첫차·막차 | POST /api/notifications/transit | GET /api/notifications/transit |

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

`scheduleType`은 FIRST_TRANSIT 또는 LAST_TRANSIT 필수. 목표 도착 시간은 생략한다. 입력해도 저장되지 않는다. 기존 `/arrival`에서도 동일하게 동작한다.

```json
{
  "routeName": "퇴근 막차",
  "scheduleType": "LAST_TRANSIT",
  "reminderOffsetMinutes": [10],
  "repeatDays": ["MON", "TUE", "WED", "THU", "FRI"],
  "routeDetails": "검색 결과의 경로 후보 하나를 JSON.stringify한 문자열로 교체"
}
```

두 예제의 routeDetails는 자리표시자다. 실제 검색 경로의 정류장 목록·좌표·provider를 보존해 넣어야 한다.

등록 응답은 기존과 같이 HTTP 200과 생성 ID다. 목록/상세 응답에는 `category: SCHEDULE | TRANSIT`와 `scheduleType: NORMAL | FIRST_TRANSIT | LAST_TRANSIT`가 함께 포함된다. 첫·막차의 targetArrivalTime은 null이며 프론트에서 목표 도착 시간 대신 첫차/막차 표시를 사용한다. 각 목록은 요청한 사용자의 해당 분류만 반환한다.

수정·삭제·상태 변경은 기존 `/arrival/{id}`를 재사용한다. NORMAL로 변경하려면 도착 시간이 필요하고, 첫·막차로 변경하면 기존 도착 시간은 지운다.

## 푸시 확인

1. 브라우저에서 온에타 알림 권한을 허용하고 로그인한다.
2. POST `/api/notifications/device-tokens`로 실제 FCM 토큰을 등록한다.
3. POST `/api/notifications/test`를 본인 로그인 토큰으로 호출한다. 요청 본문은 없다.
4. `data: FCM_ACCEPTED`는 Firebase 서버가 발송 요청을 수락했다는 의미다. 브라우저에 알림이 표시됐는지는 별도로 확인한다.

테스트 API는 다른 사용자/임의 토큰을 지정할 수 없으며 사용자별 1분 간격으로 제한한다. 등록 토큰이 없으면 400, Firebase 비활성화·발송 실패면 503 N004다. Firebase가 꺼진 상태를 발송 성공으로 처리하지 않는다.

서버 환경에 `APP_FIREBASE_ENABLED=true`, `FIREBASE_SERVICE_ACCOUNT=file:/절대경로/서비스계정.json`을 설정한다. Android `google-services.json`이나 웹 firebaseConfig는 서버 인증 파일이 아니다. 서비스 계정 파일과 application*.yml 변경은 커밋하지 않는다.

공식 문서: https://firebase.google.com/docs/admin/setup
