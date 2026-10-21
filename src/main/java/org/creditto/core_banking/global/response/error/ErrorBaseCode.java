package org.creditto.core_banking.global.response.error;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ErrorBaseCode implements ErrorCode {

    /**
     * 400 BAD_REQUEST - 잘못된 요청
     */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, 400, "잘못된 요청입니다."),
    BAD_REQUEST_ILLEGALARGUMENTS(HttpStatus.BAD_REQUEST, 40010, "잘못된 요청입니다."),
    MISSING_PARAM(HttpStatus.BAD_REQUEST, 400, "필수 파라미터가 존재하지 않습니다."),
    NOT_READABLE(HttpStatus.BAD_REQUEST, 400, "JSON 혹은 REQUEST BODY 필드 오류 입니다."),
    INVALID_GOOGLE_CODE(HttpStatus.BAD_REQUEST, 400, "유효하지 않은 코드입니다."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, 400, "BODY 유효성 검사 실패 오류입니다."),
    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST, 40001, "계좌 잔액이 부족합니다."),
    INVALID_ACCOUNT_TYPE(HttpStatus.BAD_REQUEST, 40002, "해당 거래에 유효하지 않은 계좌 종류입니다."),
    PASSWORD_REPEATING_DIGITS(HttpStatus.BAD_REQUEST, 40003, "반복되는 숫자는 비밀번호로 사용할 수 없습니다."),
    PASSWORD_SEQUENTIAL_DIGITS(HttpStatus.BAD_REQUEST, 40004, "연속되는 숫자는 비밀번호로 사용할 수 없습니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, 40005, "비밀번호가 일치하지 않습니다."),
    MISMATCH_PASSWORD(HttpStatus.BAD_REQUEST, 40006, "비밀번호와 비밀번호 확인이 일치하지 않습니다."),
    TRANSACTION_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, 40020, "1회 거래 한도를 초과했습니다."),
    DAILY_REMITTANCE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, 40021, "일일 송금 한도를 초과했습니다."),
    INVALID_RECIPIENT_INFORMATION(HttpStatus.BAD_REQUEST, 40030, "잘못된 수취인 정보입니다."),
    EXCHANGE_RATE_EXPIRED(HttpStatus.BAD_REQUEST, 40040, "환율 유효시간이 만료되었습니다."),
    CURRENCY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, 40041, "지원하지 않는 통화입니다."),
    SAME_CURRENCY_EXCHANGE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, 40042, "환전 전 통화와 환전 후 통화가 같을 수 없습니다."),
    INVALID_REGULAR_REMITTANCE_STATUS(HttpStatus.BAD_REQUEST, 40050, "변경할 수 없는 정기송금 상태입니다."),
    BAD_REQUEST_EMPTY_BODY(HttpStatus.BAD_REQUEST, 40011, "요청 본문이 비어있거나 형식이 올바르지 않습니다."),

    /**
     * 401 UNAUTHORIZED - 리소스 접근 권한
     */
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, 401, "토큰이 만료되었습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, 401, "인증되지 않은 사용자입니다."),
    UNENROLLED(HttpStatus.UNAUTHORIZED, 40101, "가입되지 않은 사용자입니다."),
    INVALID_GOOGLE_AUTHCODE(HttpStatus.UNAUTHORIZED, 401, "유효하지 않은 인증코드입니다."),
    INVALID_GOOGLE_TOKEN(HttpStatus.UNAUTHORIZED, 401, "유효하지 않은 토큰입니다."),

    /**
     * 403 FORBIDDEN - 리소스 접근 금지
     */
    FORBIDDEN(HttpStatus.FORBIDDEN, 403, "리소스 접근이 거부되었습니다."),
    INACTIVE_OR_LOCKED_ACCOUNT(HttpStatus.FORBIDDEN, 40301, "휴면 또는 잠금 상태의 계좌입니다."),

    /**
     * 404 NOT FOUND - 찾을 수 없음
     */
    NOT_FOUND_API(HttpStatus.NOT_FOUND, 404, "잘못된 API 요청입니다."),
    NOT_FOUND_ENTITY(HttpStatus.NOT_FOUND, 404, "대상을 찾을 수 없습니다."),
    NOT_FOUND_ACCOUNT(HttpStatus.NOT_FOUND, 40401, "해당 계좌를 찾을 수 없습니다."),
    NOT_FOUND_TRANSACTION_TYPE(HttpStatus.NOT_FOUND, 40402, "해당 거래 타입을 찾을 수 없습니다."),
    REGULAR_REMITTANCE_NOT_FOUND(HttpStatus.NOT_FOUND, 40411, "해당 자동이체 내역을 찾을 수 없습니다."),
    NOT_FOUND_RECIPIENT(HttpStatus.NOT_FOUND, 40403, "해당 수취인을 찾을 수 없습니다."),
    NOT_FOUND_REGULAR_REMITTANCE(HttpStatus.NOT_FOUND, 40404, "정기송금 정보를 찾을 수 없습니다."),
    NOT_FOUND_EXCHANGE_RECORD(HttpStatus.NOT_FOUND, 40405, "환전 내역을 찾을 수 없습니다."),


    /**
     * 405 METHOD NOT ALLOWED - 허용되지 않은 메서드
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, 405, "잘못된 HTTP METHOD 요청입니다."),

    /**
     * 409 CONFLICT
     */
    CONFLICT(HttpStatus.CONFLICT, 409, "이미 존재하는 리소스입니다."),
    DB_CONFLICT(HttpStatus.CONFLICT, 409, "DB 관련 충돌 문제입니다."),
    DUPLICATE_REMITTANCE(HttpStatus.CONFLICT, 40911, "동일한 내용의 자동이체가 이미 등록되어 있습니다."),
    REGULAR_REMITTANCE_ALREADY_EXECUTED(HttpStatus.CONFLICT, 40912, "오늘 이미 실행된 정기송금입니다."),
    ACCOUNT_LOCK_TIMEOUT(HttpStatus.LOCKED, 42301, "계좌 처리 대기 시간이 초과되었습니다."),
    ACCOUNT_LOCK_INTERRUPTED(HttpStatus.LOCKED, 42302, "계좌 잠금 처리 중 오류가 발생했습니다."),

    /**
     * 500 INTERNAL SERVER ERROR - 서버 오류
     */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 500, "서버 내부 오류입니다."),
    URL_DECODE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 500, "URL 디코딩 에러"),
    TRANSACTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 50001, "거래 중 오류가 발생했습니다."),

    /**
     * 501 NOT IMPLEMENTED - 구현되지 않음
     */
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, 501, "서버 내부 오류 입니다."),


    /**
     * 503 SERVICE UNAVAILABLE
     */
    EXCHANGE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, 50301, "환전 가능 시간이 아닙니다.");


    private final HttpStatus httpStatus;
    private final int code;
    private final String message;

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
