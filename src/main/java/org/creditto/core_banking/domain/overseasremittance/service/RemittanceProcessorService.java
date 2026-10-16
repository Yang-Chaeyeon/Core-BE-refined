package org.creditto.core_banking.domain.overseasremittance.service;

import lombok.RequiredArgsConstructor;
import org.creditto.core_banking.common.vo.Money;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.account.service.AccountLockService;
import org.creditto.core_banking.domain.exchange.dto.ExchangeReq;
import org.creditto.core_banking.domain.exchange.dto.ExchangeRes;
import org.creditto.core_banking.domain.exchange.entity.Exchange;
import org.creditto.core_banking.domain.exchange.repository.ExchangeRepository;
import org.creditto.core_banking.domain.exchange.service.ExchangeService;
import org.creditto.core_banking.domain.overseasremittance.dto.ExecuteRemittanceCommand;
import org.creditto.core_banking.domain.overseasremittance.dto.OverseasRemittanceResponseDto;
import org.creditto.core_banking.domain.overseasremittance.entity.OverseasRemittance;
import org.creditto.core_banking.domain.overseasremittance.repository.OverseasRemittanceRepository;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.recipient.repository.RecipientRepository;
import org.creditto.core_banking.domain.regularremittance.entity.RegularRemittance;
import org.creditto.core_banking.domain.regularremittance.repository.RegularRemittanceRepository;
import org.creditto.core_banking.domain.remittancefee.dto.RemittanceFeeReq;
import org.creditto.core_banking.domain.remittancefee.entity.FeeRecord;
import org.creditto.core_banking.domain.remittancefee.service.RemittanceFeeService;
import org.creditto.core_banking.domain.transaction.entity.TxnResult;
import org.creditto.core_banking.domain.transaction.entity.TxnType;
import org.creditto.core_banking.domain.transaction.service.TransactionService;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.response.error.ErrorBaseCode;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.creditto.core_banking.global.response.error.ErrorBaseCode.*;

/**
 * 해외송금의 핵심 비즈니스 로직을 실제로 실행하는 Domain Service 입니다.
 * 이 서비스는 외부와 격리되어, 오직 내부 Command 객체({@link ExecuteRemittanceCommand})만을 받아
 * 송금 실행에 필요한 모든 도메인 규칙(엔티티 조회, 잔액 확인, 출금, 거래 내역 생성 등)을 트랜잭션 내에서 수행합니다.
 */
@Service
@RequiredArgsConstructor
public class RemittanceProcessorService {

    private final OverseasRemittanceRepository remittanceRepository;
    private final AccountRepository accountRepository;
    private final RecipientRepository recipientRepository;
    private final RegularRemittanceRepository regularRemittanceRepository;
    private final ExchangeRepository exchangeRepository;
    private final ExchangeService exchangeService;
    private final TransactionService transactionService;
    private final RemittanceFeeService remittanceFeeService;
    private final AccountLockService accountLockService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 전달된 Command를 기반으로 해외송금의 모든 단계를 실행합니다.
     * 1. Command에 포함된 ID를 사용하여 관련 엔티티(계좌, 수취인 등)를 조회합니다.
     * 2. ExchangeService를 통해 환전 처리 및 결과(DTO)를 수신합니다.
     * 3. RemittanceFeeService를 통해 수수료를 계산하고, 계좌 잔액을 확인합니다.
     * 4. 해외송금(OverseasRemittance) 엔티티를 생성하고 저장합니다.
     * 5. 실제 계좌에서 수수료 및 송금액을 출금하고, 각 출금에 대한 거래(Transaction) 내역을 생성합니다.
     *
     * @param command 송금 실행에 필요한 모든 데이터가 포함된 Command 객체
     * @return 송금 처리 결과를 담은 응답 DTO
     * @throws CustomBaseException 잔액이 부족하거나 지원하지 않는 통화인 경우 발생
     * @throws IllegalArgumentException 관련 엔티티를 찾을 수 없는 경우 발생
     */
    public OverseasRemittanceResponseDto execute(final ExecuteRemittanceCommand command) {
        // 계좌 락 → 트랜잭션 시작 → 커밋 → 계좌 락 해제 순서를 보장하기 위해 락 안에서 트랜잭션을 시작
        return accountLockService.executeWithLock(
                command.accountId(),
                () -> transactionTemplate.execute(status -> executeInTransaction(command))
        );
    }

    private OverseasRemittanceResponseDto executeInTransaction(final ExecuteRemittanceCommand command) {
        Account account = accountRepository.findById(command.accountId())
                .orElseThrow(() -> new CustomBaseException(NOT_FOUND_ACCOUNT));

        Recipient recipient = recipientRepository.findById(command.recipientId())
                .orElseThrow(() -> new CustomBaseException(NOT_FOUND_RECIPIENT));

        Long userId = account.getUserId();


        // 정기 송금 정보 조회 (regRemId가 있을 경우)
        RegularRemittance regularRemittance = Optional.ofNullable(command.regRemId())
                .map(id -> regularRemittanceRepository.findById(id)
                        .orElseThrow(() -> new CustomBaseException(NOT_FOUND_REGULAR_REMITTANCE)))
                .orElse(null);

        // 1. ExchangeService를 통해 환전 처리 및 결과(DTO) 수신
        ExchangeRes exchangeRes = exchange(userId, command);

        // 실제 송금해야 할 금액
        BigDecimal actualSendAmount = exchangeRes.exchangeAmount();
        Money sendAmountMoney = Money.of(actualSendAmount, CurrencyCode.KRW);

        // 수수료 계산
        FeeRecord feeRecord = calculateFee(exchangeRes, command.receiveCurrency());

        // 총 수수료
        BigDecimal totalFee = feeRecord.getTotalFee();
        Money totalFeeMoney = Money.of(totalFee, CurrencyCode.KRW);

        // 총 차감될 금액 계산 (실제 보낼 금액 + 총 수수료)
        Money totalDeductionMoney = sendAmountMoney.plus(totalFeeMoney);

        // 3. DTO에 담겨올 ID로 Exchange 엔티티 다시 조회
        Long exchangeId = exchangeRes.exchangeId();

        Exchange savedExchange = exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> new CustomBaseException(NOT_FOUND_EXCHANGE_RECORD));

        Account lockedAccount = accountRepository.findByIdForUpdate(command.accountId())
                .orElseThrow(() -> new CustomBaseException(NOT_FOUND_ACCOUNT));

        if (!lockedAccount.checkSufficientBalance(totalDeductionMoney)) {
            transactionService.recordFailure(lockedAccount, actualSendAmount, TxnType.WITHDRAWAL, null);
            throw new CustomBaseException(ErrorBaseCode.INSUFFICIENT_FUNDS);
        }

        OverseasRemittance overseasRemittance = OverseasRemittance.of(
                recipient,
                lockedAccount,
                regularRemittance,
                savedExchange,
                feeRecord,
                actualSendAmount,
                command
        );
        remittanceRepository.save(overseasRemittance);

        if (totalFee.compareTo(BigDecimal.ZERO) > 0) {
            lockedAccount.withdraw(totalFeeMoney);
            transactionService.saveTransaction(lockedAccount, totalFee, TxnType.FEE, overseasRemittance.getRemittanceId(), TxnResult.SUCCESS);
        }

        lockedAccount.withdraw(sendAmountMoney);
        transactionService.saveTransaction(lockedAccount, actualSendAmount, TxnType.WITHDRAWAL, overseasRemittance.getRemittanceId(), TxnResult.SUCCESS);

        accountRepository.save(lockedAccount);

        return OverseasRemittanceResponseDto.from(overseasRemittance);
    }

    private ExchangeRes exchange(Long userId, ExecuteRemittanceCommand command) {
        ExchangeReq exchangeReq = ExchangeReq.of(command.sendCurrency(), command.receiveCurrency(), command.targetAmount());
        return exchangeService.exchange(userId, exchangeReq);
    }

    private FeeRecord calculateFee(ExchangeRes exchangeRes, CurrencyCode currencyCode) {
        RemittanceFeeReq feeReq = RemittanceFeeReq.of(
                exchangeRes.exchangeRate(),
                exchangeRes.exchangeAmount(),  // 실제 보낼 금액으로 수수료 계산
                currencyCode,
                exchangeRes.fromAmountInUSD() // 환전 결과에서 USD 환율 가져오기
        );
        return remittanceFeeService.calculateAndSaveFee(feeReq);
    }
}
