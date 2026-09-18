package com.OnETA.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C001", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C002", "잘못된 입력값입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "허용되지 않는 HTTP 메서드입니다."),
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "C004", "잘못된 타입입니다."),
    HANDLE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "C005", "접근이 거부되었습니다."),
    MISSING_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "C006", "필수 파라미터가 누락되었습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "C007", "인증이 필요합니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U001", "사용자를 찾을 수 없습니다."),

    // Address
    ADDRESS_SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A005", "주소 검색 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "주소를 찾을 수 없습니다."),
    ADDRESS_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "A002", "주소는 최대 5개까지 등록할 수 있습니다."),
    CURRENT_ADDRESS_NOT_SET(HttpStatus.NOT_FOUND, "A003", "현재 설정된 주소가 없습니다."),
    ADDRESS_ALREADY_EXISTS(HttpStatus.CONFLICT, "A004", "동일한 위치의 주소가 이미 등록되어 있습니다."),

    // Transit
    TRANSIT_ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "T001", "검색 가능한 대중교통 경로가 없습니다."),
    TRANSIT_ROUTE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "T002", "현재 지원하지 않는 교통수단 또는 경로 형식입니다."),
    TRANSIT_API_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "T003", "경로 검색 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    TRANSIT_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "T004", "경로 검색 서비스에서 올바르지 않은 응답을 받았습니다."),
    TRANSIT_SCHEDULE_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "T005", "첫차·막차 시간표를 확인할 수 없는 경로입니다. 서울 버스·지하철 지원 범위를 확인해주세요."),
    TRANSIT_SCHEDULE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "T006", "대중교통 시간표 조회에 실패했습니다. 잠시 후 다시 시도해주세요."),
    TRANSIT_CONNECTION_UNVERIFIED(HttpStatus.UNPROCESSABLE_ENTITY, "T007", "첫차·막차 시각만으로 환승 연결을 확인할 수 없습니다. 중간 운행편 시간표가 필요한 경로입니다."),
    DEVICE_TOKEN_CONFLICT(HttpStatus.CONFLICT, "N003", "이미 다른 사용자에게 등록된 디바이스 토큰입니다."),
    PUSH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "N004", "푸시 발송에 실패했습니다. 서버 설정과 디바이스 토큰을 확인해주세요."),

    // Notification
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "N001", "공지사항을 찾을 수 없습니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N002", "알림을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

}
