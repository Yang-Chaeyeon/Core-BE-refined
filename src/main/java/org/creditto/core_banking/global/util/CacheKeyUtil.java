package org.creditto.core_banking.global.util;

/**
 * Redis 캐시 키를 한 곳에서 만들기 위한 유틸리티입니다.
 * 키 형식이 여러 클래스에 흩어지면 조회와 무효화가 서로 다른 키를 쓸 위험이 있어 여기서만 생성합니다.
 */
public final class CacheKeyUtil {

    private static final String TOTAL_BALANCE_PREFIX = "totalBalance::";

    private CacheKeyUtil() {
    }

    /**
     * 사용자 전체 계좌 총 잔액 캐시 키를 반환합니다.
     *
     * @param userId 사용자 ID
     * @return {@code totalBalance::{userId}}
     */
    public static String getTotalBalanceKey(Long userId) {
        return TOTAL_BALANCE_PREFIX + userId;
    }
}
