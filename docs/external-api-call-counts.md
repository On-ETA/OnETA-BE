# 외부 호출 횟수 로그

각 호출 시도 직전에 INFO 로그를 기록합니다.

```text
========== [API 호출량] KAKAO | 오늘 12회 / 누적 12회 | API=keyword-search 오늘 12회 / 누적 12회 | 2026-09-15 (KST) ==========
```

- providerTotal: 서버 실행 이후 해당 서비스 전체 호출 시도 횟수
- providerToday: 한국 시간 기준 당일 서비스 전체 호출 시도 횟수
- apiTotal / apiToday: 해당 API별 누적 / 당일 호출 시도 횟수
- 대상: 카카오 검색, ODsay 경로·운행정보, 서울 버스 도착·위치·노선·노선 정류장, TAGO, FCM 발송, SMTP 이메일 발송

외부 요청 없이 캐시를 반환하거나 입력 검증에서 거절한 경우는 포함하지 않습니다.
호출 실패는 포함합니다. SDK 내부 재시도 및 OAuth 라이브러리 내부 호출은 개별 집계하지 않습니다.
키, 토큰, 검색어, 이메일, 요청 쿼리 문자열은 이 로그에 남기지 않습니다.

카운트는 JVM 메모리에 보관하므로 서버 재시작 시 초기화되고, 여러 서버는 각각 집계합니다.
당일 카운트는 한국 시간 자정 이후 첫 호출에서 초기화합니다.
공급자의 일일 한도 초기화 시각 및 과금 기준과 다를 수 있으며, 공식 사용량은 공급자 콘솔에서 확인합니다.
이 기능은 로그 집계이며 호출 제한을 강제하거나 사용량을 영구 저장하지 않습니다.

## 스케줄러 실행 요약

`[스케줄러 호출량]`으로 검색하면 도착 알림 확인, 버스 위치 확인, 서울 버스 노선 동기화, 알림 발송 재시도별 실행 요약을 확인할 수 있습니다.
이번 실행은 동일 스레드의 호출만 집계하므로 다른 HTTP 요청이나 동시에 실행 중인 작업은 섞이지 않습니다. 예약 재시도는 별도 실행입니다.
오늘 누적은 해당 서버의 전체 서비스 호출 횟수이며, 스케줄러 외 요청도 포함합니다.
호출이 없으면 0회이며 확인된 처리 대상 없음·활성 알림 없음·처리 후보·공공 교통 캐시 사용 상태를 표시합니다. 모든 캐시 종류를 계측하는 것은 아닙니다.
실행 종료는 발송 성공을 뜻하지 않습니다. 개별 항목 오류는 기존 오류 로그를 함께 확인하세요.
도착 알림 스케줄러는 com.HomeRun 패키지에 있어 애플리케이션에 명시적으로 Import하여 등록합니다.


## 일일 한도와 사용률

로그의 사용률은 한국 시간 당일 서버 집계 / 일일 한도 × 100입니다. 100% 초과도 그대로 표시하며 호출을 차단하지 않습니다.
기본값은 2026-09-15 공식 문서에서 확인한 무료/개발계정 참고 한도입니다. 실제 계정의 승인 한도를 확인한 값이 아닙니다.

| 그룹 | 참고 한도/일 | 설정 환경변수 |
|---|---:|---|
| ODSAY | 1,000 | API_DAILY_LIMIT_ODSAY |
| KAKAO (장소 검색) | 100,000 | API_DAILY_LIMIT_KAKAO |
| SEOUL_ARRIVAL | 1,000 | API_DAILY_LIMIT_SEOUL_ARRIVAL |
| SEOUL_LOCATION | 1,000 | API_DAILY_LIMIT_SEOUL_LOCATION |
| SEOUL_ROUTES (노선·경유 정류소 합산) | 1,000 | API_DAILY_LIMIT_SEOUL_ROUTES |
| TAGO_ARRIVAL | 10,000 | API_DAILY_LIMIT_TAGO_ARRIVAL |
| TAGO_STATIONS | 10,000 | API_DAILY_LIMIT_TAGO_STATIONS |
| SMTP | 계정별 확인 필요 | API_DAILY_LIMIT_SMTP |

위 변수를 서버 환경변수 또는 해당 프로필이 읽는 .env 파일에 설정하고 재시작하면 설정 한도로 표시합니다. 0이면 계산 불가로 표시합니다.
카카오 무료 쿼터는 계정의 첫 활성화 앱 등 조건이 있으며 ODsay는 요금제별로 다릅니다. 서울 노선 그룹은 두 오퍼레이션을 보수적으로 합산한 참고 집계입니다. 승인 상세의 오퍼레이션별 한도와 다를 수 있습니다.
FCM은 분당 쿼터를 일일 한도로 환산하지 않으며 일일 사용률 해당 없음으로 표시합니다. SMTP는 발송 호출 시도 수로, 수신자 수 기준 한도와 같지 않을 수 있습니다.
재시작 전 사용량, 다른 서버 및 같은 키를 사용하는 다른 앱의 호출은 포함되지 않으므로 공급자의 공식 사용률이 아닙니다.

출처:
- https://lab.odsay.com/community/boardView?seq=684
- https://developers.kakao.com/docs/en/getting-started/quota
- https://www.data.go.kr/data/15000314/openapi.do
- https://www.data.go.kr/data/15000332/openapi.do
- https://www.data.go.kr/data/15000193/openapi.do
- https://www.data.go.kr/data/15098530/openapi.do
- https://www.data.go.kr/data/15098534/openapi.do
- https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas
