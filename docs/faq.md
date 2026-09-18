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
