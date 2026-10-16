package org.creditto.core_banking.domain.overseasremittance.dto;
import org.creditto.core_banking.domain.regularremittance.entity.RegularRemittance;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 서비스 계층 내부에서 해외송금 실행에 필요한 모든 데이터를 전달하는 불변 Command 객체입니다.
 * CQRS 패턴의 Command에 해당하며, 송금 처리에 필요한 모든 정보를 포함합니다.
 * 이 객체는 순수한 데이터 컨테이너이며, 생성 로직은 Application Service 계층에서 처리됩니다.
 */
public record ExecuteRemittanceCommand(
        // 고객 식별자
        Long userId,

        // 수취인 엔티티의 식별자
        Long recipientId,

        // 출금계좌 엔티티의 식별자
        Long accountId,

        // 정기송금 엔티티의 식별자 (일회성 송금의 경우 null)
        @Nullable Long regRemId,

        // 보내는 통화 (e.g., "KRW")
        CurrencyCode sendCurrency,

        // 받는 통화 (e.g., "USD")
        CurrencyCode receiveCurrency,

        // 보내는 금액 (수취 통화 기준)
        BigDecimal targetAmount,

        // 송금 시작일 (정기송금의 경우 사용)
        LocalDate startDate
) {

    public static ExecuteRemittanceCommand of(
            Long userId,
            Long recipientId,
            Long accountId,
            Long regRemId,
            CurrencyCode sendCurrency,
            CurrencyCode receiveCurrency,
            BigDecimal targetAmount,
            LocalDate startDate
    ) {
        return new ExecuteRemittanceCommand(
                userId,
                recipientId,
                accountId,
                regRemId,
                sendCurrency,
                receiveCurrency,
                targetAmount,
                startDate
        );
    }

    // executionDate: 배치 시작 시점의 기준일 (중복 실행 확인과 실행일 기록에 같은 날짜를 쓰기 위해 외부에서 받음)
    public static ExecuteRemittanceCommand of(RegularRemittance regularRemittance, LocalDate executionDate) {
        return new ExecuteRemittanceCommand(
                regularRemittance.getAccount().getUserId(),
                regularRemittance.getRecipient().getRecipientId(),
                regularRemittance.getAccount().getId(),
                regularRemittance.getRegRemId(),
                regularRemittance.getSendCurrency(),
                regularRemittance.getReceivedCurrency(),
                regularRemittance.getSendAmount(),
                executionDate
        );
    }
}
