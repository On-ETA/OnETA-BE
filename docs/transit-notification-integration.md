# 경로 조회·첫차/막차·FCM 연동

## 경로 후보 조회

`GET /api/transit/routes/search`의 기존 필드를 유지하고 `provider` (`ODSAY` / `KAKAO`)를 추가한다.

| HTTP | code | 의미 | 프론트 처리 |
|---|---|---|---|
| 404 | T001 | 경로 없음 또는 검색 가능한 정류장/서비스 지역 없음 | 경로 없음 안내 |
| 422 | T002 | 현재 지원하지 않는 도시 간 경로/교통수단 | 미지원 안내 |
| 503 | T003 | ODsay 호출 한도, 인증, 통신 등 외부 서비스 오류 | 일시적 사용 불가 안내, 즉시 반복 호출 금지 |
| 502 | T004 | JSON 파싱 실패 또는 예상과 다른 응답 구조 | 재시도 안내 |
| 400 | C002 | 잘못된 좌표 또는 출발지·도착지 간 거리가 너무 가까움 | 입력 수정 안내 |

ODsay `error`의 객체/배열 형태를 모두 처리한다. 공급자의 원본 메시지나 요청 URL/API 키는 응답에 노출하지 않는다.
ODsay 도시 간 응답은 단순한 도시 내 `result.path`와 달라 카카오로 재검색한다. 카카오가 BUS 구간으로 제공하는 시외버스는 경로 후보에 포함할 수 있다. 알 수 없는 교통수단을 도보로 변환하거나 일부 구간만 완전한 경로로 제공하지 않는다.

### 카카오 대체 호출

- ODsay가 정상 응답하면 카카오를 호출하지 않는다. 한도·인증·통신 오류, 응답 형식 오류, 경로 없음 또는 미지원 응답이면 카카오를 1회 호출한다. 입력 오류(C002)는 대체 호출하지 않는다.
- 기존 `KAKAO_REST_API_KEY`를 재사용한다. 키가 없거나 `kakao.transit.fallback-enabled=false`이면 기존 ODsay 오류를 반환한다. 기본값은 true이며 유료 API 설정을 변경하지 않는다.
- `GET https://dapi.kakao.com/v2/routing/publictraffic`에 좌표를 전달한다. 두 공급자 모두 연결 3초, 읽기 10초 제한을 적용한다. 별도 자동 재시도는 없다.
- 최대 3개 후보를 반환한다. 초 단위 소요시간은 분 단위로 올림하고, `WALKING`은 `WALK`로 변환한다. 버스·지하철의 정류장 이름 목록과 구간 양 끝 좌표를 보존한다. 복수 노선 대안은 기존 모델에 맞춰 첫 노선 이름을 사용한다.
- 요금은 `fare.value`, 없으면 `fare.min`을 사용하고, 요금 정보 자체가 없으면 `totalCost=null`이다. 프론트는 null을 무료로 표시하지 않고 '요금 정보 없음'으로 처리해야 한다.
- `routeId`는 `KAKAO_` 접두사, `provider`는 `KAKAO`다. `routeDetails`에는 이 필드를 포함한 경로 객체 전체를 저장한다. ODsay·BIS 식별자는 만들어 넣지 않는다.
- 카카오 경로의 `realTimeDurationMinutes`는 예상 소요시간과 같다. 저장 후 NORMAL 알림 재계산에도 실시간 BIS 조회를 시도하지 않는다. 첫차·막차는 [서울 버스 단일 탑승 경로](seoul-bus-first-last.md)에 한해 정류장·방향·금일 시간표 검증 후 등록/수정을 허용한다. 나머지는 422 `T005`, 서울 시간표 API 조회 실패는 503 `T006`이다.
- 두 공급자가 모두 실패하면 마지막 카카오 오류를 반환한다(경로 없음 T001, 미지원 T002, 통신/인증/한도 T003, 잘못된 응답 T004). 키나 공급자 원문 오류는 반환하지 않는다.
- 호출 집계는 주소 검색의 `KAKAO`와 `KAKAO_TRANSIT`을 분리한다. 대중교통 참고 한도는 1,000이며 실제 승인 한도는 `API_DAILY_LIMIT_KAKAO_TRANSIT`로 지정한다. 서버 집계는 공식 쿼터/과금 수치가 아니다.

변환 명세: [카카오맵 REST API](https://developers.kakao.com/docs/ko/kakaomap/rest-api), 대중교통 경로 조회.

근거: [ODsay 공식 API 명세](https://lab.odsay.com/guide/releaseReference), 대중교통 길찾기 v1.7 (`searchPubTransPathR`).

2026-09-18 첨부 좌표(127.05593797036339, 35.99330835090628 → 127.060175955621, 37.2042695838843)로 실제 호출 시 `error: [{code: "429", message: "Daily quota exceeded"}]`가 반환되었다. 사진 당시의 경로 응답은 재현하지 못했으므로 당시 원인을 도시 간 응답으로 확정하지 않는다. 호출 한도 회복 후 동일 좌표를 다시 확인해야 한다.

## 경로 등록 및 수정

`POST /api/notifications/arrival`, `PATCH /api/notifications/arrival/{id}`

| scheduleType | targetArrivalTime |
|---|---|
| NORMAL | 필수, `HH:mm:ss` |
| FIRST_TRANSIT | 생략 가능. 보내더라도 사용하지 않고 null 저장 |
| LAST_TRANSIT | 생략 가능. 보내더라도 사용하지 않고 null 저장 |

- 생성 시 `scheduleType` 생략은 `NORMAL`이다. 수정 시 생략은 기존 유형을 유지한다.
- 첫차/막차로 전환하면 기존 목표 도착 시간을 제거한다.
- 첫차/막차에서 NORMAL로 전환할 때는 목표 도착 시간을 함께 보내야 한다.
- NORMAL의 부분 수정에서 목표 도착 시간 생략/null은 기존 값을 유지한다.
- 조회 응답에서 첫차/막차의 `targetArrivalTime`은 null이므로 프론트는 해당 입력과 표시를 비활성화한다.
- `routeDetails`는 조회한 경로 객체를 JSON 문자열로 직렬화한 값이다. 아래 예시의 자리표시자는 실제 경로로 교체한다.

```json
{
  "routeName": "첫차 출근",
  "scheduleType": "FIRST_TRANSIT",
  "reminderOffsetMinutes": [10],
  "repeatDays": ["MON", "TUE", "WED", "THU", "FRI"],
  "routeDetails": "<경로 후보 객체를 JSON.stringify한 문자열>"
}
```

DB에는 Flyway V16을 적용해야 한다. 이 마이그레이션은 `target_arrival_time`에 null을 허용하고 기존 첫차/막차의 사용하지 않는 목표 도착 시간을 null로 정리한다. 운영 DB에 수동 실행하거나 배포한 것은 아니다.

## FCM 토큰 등록

```http
POST /api/notifications/device-tokens
Authorization: Bearer <accessToken>
Content-Type: application/json

{"deviceToken":"<FCM registration token>"}
```

- 기존처럼 사용자당 토큰 1개를 유지한다. 동일 토큰 재등록은 중복 행을 만들지 않으며 새 토큰은 기존 값을 교체한다.
- 누락/빈 값/공백 포함/255자 초과는 400 C002다. 255자는 현재 DB 컬럼 제약이다.
- 다른 계정 소유 토큰은 신규·갱신 모두 409 N003이다. 다른 사용자의 토큰을 삭제하거나 자동 이전하지 않는다. 동일 브라우저의 계정 전환은 프론트에서 기존 FCM 토큰을 폐기하고 새 토큰을 발급받아 등록해야 한다.

## 실제 수신 확인 절차

1. 테스트 서버의 유효 설정에서 `app.firebase.enabled=true`인지 확인한다. 현재 로컬 작업본의 `application-prod.yml`은 false이며, 환경변수 `APP_FIREBASE_ENABLED=true` 등으로 명시적으로 켜야 한다. 설정 파일은 이번 변경에서 수정하지 않았다.
2. 현재 `FirebaseConfig`가 사용하는 classpath의 `firebase-service-account.json`을 준비한다. 서버 서비스 계정과 프론트 Firebase 프로젝트가 같은지 확인한다. 서비스 계정 키를 프론트나 로그에 노출하지 않는다.
3. HTTPS/localhost 브라우저에서 알림 권한, 서비스 워커, 실제 FCM 토큰 발급을 확인한 뒤 위 API로 등록한다.
4. 테스트 계정의 알림을 가까운 미래로 설정한다. NORMAL은 목표 도착 시간에서 실제 이동 시간과 미리 알림 시간을 뺀 시각에 발송 후보가 된다. 경로 외부 API가 정상 동작해야 한다.
5. 서버 FCM messageId와 delivery 상태를 확인하고 브라우저의 포그라운드/백그라운드 수신을 각각 확인한다. 토큰 등록 성공이나 delivery SENT만으로 브라우저 표시 성공을 판정하지 않는다. 특히 현재 FCM 비활성 모드는 전송을 생략하고 반환하므로 SENT가 실제 전송을 증명하지 않는다.

이번 자동 테스트는 외부 FCM 실제 발송을 수행하지 않는다. 사용자가 지정한 `https://on-eta.com` / Chrome 환경에서 확인을 시도했으나, Computer Use가 현재 브라우저 URL을 신뢰할 수 있게 판별하지 못해 브라우저 조작을 중단했다. 토큰 등록과 실제 기기 수신은 아직 확인되지 않았다.

발송 완료/단건 종료 처리에서 Hibernate 프록시를 실제 엔티티로 풀어 저장하도록 수정했다. 기존 직접 형변환은 발송 성공 후에도 예외를 발생시켜 재시도로 이어질 수 있었다. 동시 발송 통합 테스트는 FCM을 모의 처리하며 1회 전송과 SENT 저장을 검증한다.
