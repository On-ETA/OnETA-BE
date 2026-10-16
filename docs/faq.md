# FAQ

## 조회

- `GET /api/mypage/faqs` (JWT 인증 필요)
- 공개 항목만 `display_order ASC, id ASC` 순으로 반환합니다.
- 분류, 검색, 페이징, 별도 상세 조회 및 관리자 API는 제공하지 않습니다.
- 답변은 줄바꿈을 포함할 수 있는 일반 텍스트입니다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "id": 1,
      "question": "질문",
      "answer": "답변"
    }
  ]
}
```

공개 항목이 없으면 `data`는 `[]`입니다.

## 마이그레이션 및 데이터 관리

`V15__create_faqs.sql`이 `faqs` 테이블을 생성합니다. 기존 V1~V14는 변경하지 않습니다.
Flyway가 활성화된 환경에서는 애플리케이션 시작 시 미적용 마이그레이션이 실행됩니다.
실제 FAQ 문구가 확정되기 전이므로 초기 데이터는 포함하지 않습니다.

아래 SQL의 질문, 답변, ID는 실제 값으로 바꿔 사용합니다.

```sql
INSERT INTO faqs (question, answer, display_order, is_active)
VALUES ('질문', '답변', 10, TRUE);

UPDATE faqs SET question = '수정한 질문', answer = '수정한 답변' WHERE id = 1;
UPDATE faqs SET display_order = 20 WHERE id = 1;
UPDATE faqs SET is_active = FALSE WHERE id = 1;
```

`display_order` 기본값은 0, `is_active` 기본값은 TRUE입니다.
표시 순서가 같으면 ID가 작은 항목이 먼저 표시됩니다.

## 출시 FAQ 문구 (서비스 지원 지역)

**Q. 현재 어느 지역에서 서비스를 이용할 수 있나요?**

A. 현재 서울을 중심으로, 서울과 연결되는 일부 경기·인천 경로에서 서비스를 제공하고 있습니다. 이용 가능 여부는 선택한 출발지·도착지와 노선에 따라 달라질 수 있습니다.

현재 FAQ는 DB의 `faqs` 데이터로 노출됩니다. 아래 SQL은 **운영 DB에 수동 등록하기 위한 예시**이며, 이 문서 수정만으로 앱에 표시되지는 않습니다.

```sql
INSERT INTO faqs (question, answer, display_order, is_active)
VALUES (
  '현재 어느 지역에서 서비스를 이용할 수 있나요?',
  '현재 서울을 중심으로, 서울과 연결되는 일부 경기·인천 경로에서 서비스를 제공하고 있습니다. 이용 가능 여부는 선택한 출발지·도착지와 노선에 따라 달라질 수 있습니다.',
  90, TRUE
);
```
