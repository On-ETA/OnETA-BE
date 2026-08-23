-- 좌표만으로는 주소 관리 화면에 도로명/지번 주소를 표시할 수 없어 원문 주소를 함께 저장한다.
ALTER TABLE user_addresses
    ADD COLUMN address VARCHAR(255) NULL AFTER name;
