package org.creditto.core_banking.domain.overseasremittance;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.exchange.dto.ExchangeRateRes;
import org.creditto.core_banking.domain.overseasremittance.dto.ExecuteRemittanceCommand;
import org.creditto.core_banking.domain.overseasremittance.service.RemittanceProcessorService;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.recipient.repository.RecipientRepository;
import org.creditto.core_banking.domain.remittancefee.entity.FlatServiceFee;
import org.creditto.core_banking.domain.remittancefee.entity.NetworkFee;
import org.creditto.core_banking.domain.remittancefee.entity.PctServiceFee;
import org.creditto.core_banking.domain.remittancefee.repository.FlatServiceFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.NetworkFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.PctServiceFeeRepository;
import org.creditto.core_banking.domain.transaction.entity.Transaction;
import org.creditto.core_banking.domain.transaction.entity.TxnResult;
import org.creditto.core_banking.domain.transaction.entity.TxnType;
import org.creditto.core_banking.domain.transaction.repository.TransactionRepository;
import org.creditto.core_banking.domain.transaction.service.TransactionService;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.feign.ExchangeRateProvider;
import org.creditto.core_banking.global.response.error.ErrorBaseCode;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 송금이 실패하거나 롤백될 때 거래내역이 잔액과 어긋나지 않는지 실제 MySQL에서 확인합니다.
 *
 * <p>SUCCESS 거래내역은 잔액 변경과 같은 트랜잭션에서 저장되어 함께 커밋·롤백되고,
 * FAILURE 거래내역만 원래 트랜잭션이 끝난 뒤 별도 트랜잭션으로 저장됩니다.
 * 그 결과 어떤 경우에도 "잔액 = 초기 잔액 − SUCCESS 거래내역 합계"가 유지되어야 합니다.</p>
 */
@DisplayName("[통합] 송금 실패·롤백 시 거래내역 정합성")
class RemittanceTransactionHistoryIntegrationTest extends IntegrationTestSupport {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal SEND_AMOUNT_USD = new BigDecimal("100");

    @MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @MockitoSpyBean
    private TransactionService transactionService;

    @Autowired
    private RemittanceProcessorService remittanceProcessorService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RecipientRepository recipientRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FlatServiceFeeRepository flatServiceFeeRepository;

    @Autowired
    private PctServiceFeeRepository pctServiceFeeRepository;

    @Autowired
    private NetworkFeeRepository networkFeeRepository;

    private Recipient recipient;

    @BeforeEach
    void setUp() {
        // 외부 환율 API 대신 고정 환율을 반환
        given(exchangeRateProvider.getExchangeRates()).willReturn(Map.of(
                "USD", ExchangeRateRes.builder()
                        .currencyUnit("USD")
                        .baseRate("1,400.00")
                        .currencyName("미국 달러")
                        .build()
        ));

        // 수수료 정책은 테스트 간에 공유되므로 없을 때만 저장
        if (flatServiceFeeRepository.count() == 0) {
            flatServiceFeeRepository.save(FlatServiceFee.of(null, new BigDecimal("999999999999"), new BigDecimal("5000")));
        }
        if (pctServiceFeeRepository.count() == 0) {
            pctServiceFeeRepository.save(PctServiceFee.of(null, BigDecimal.ZERO, false));
        }
        if (networkFeeRepository.findByCurrencyCode(CurrencyCode.USD).isEmpty()) {
            networkFeeRepository.save(NetworkFee.of(null, CurrencyCode.USD, BigDecimal.ONE));
        }

        recipient = recipientRepository.save(createRecipient());
    }

    /**
     * 잔액 부족으로 송금이 실패하면 잔액은 그대로이고, FAILURE 거래내역 1건만 남는지 확인합니다.
     * FAILURE 내역은 원래 트랜잭션이 롤백된 뒤 별도 트랜잭션으로 저장되므로 롤백에 휩쓸리지 않아야 합니다.
     */
    @Test
    @DisplayName("잔액 부족으로 실패하면 잔액은 그대로이고 FAILURE 거래내역 1건만 저장된다")
    void insufficientFunds_savesOnlyFailureHistory() {
        BigDecimal smallBalance = new BigDecimal("1000");
        Account account = saveAccount(smallBalance);

        assertThatThrownBy(() -> remittanceProcessorService.execute(commandFor(account)))
                .isInstanceOf(CustomBaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorBaseCode.INSUFFICIENT_FUNDS);

        List<Transaction> histories = historiesOf(account);
        assertThat(balanceOf(account)).isEqualByComparingTo(smallBalance);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getTxnResult()).isEqualTo(TxnResult.FAILURE);
        assertThat(histories.get(0).getTxnType()).isEqualTo(TxnType.WITHDRAWAL);
    }

    /**
     * 수수료 출금과 수수료 SUCCESS 내역 저장까지 끝난 뒤 다음 단계에서 예외가 나면,
     * 수수료 출금과 함께 SUCCESS 내역도 롤백되어 "출금 성공" 유령 내역이 남지 않는지 확인합니다.
     */
    @Test
    @DisplayName("출금 도중 예외로 롤백되면 잔액은 그대로이고 SUCCESS 거래내역이 남지 않는다")
    void rollbackAfterFeeWithdrawal_leavesNoGhostSuccessHistory() {
        Account account = saveAccount(INITIAL_BALANCE);
        failOnWithdrawalHistorySave();

        assertThatThrownBy(() -> remittanceProcessorService.execute(commandFor(account)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(historiesOf(account))
                .noneMatch(history -> history.getTxnResult() == TxnResult.SUCCESS);
    }

    /**
     * 성공 · 잔액 부족 실패 · 롤백 실패가 섞여도 "잔액 = 초기 잔액 − SUCCESS 거래내역 합계"가 유지되는지 확인합니다.
     */
    @Test
    @DisplayName("성공·실패·롤백이 섞여도 잔액은 초기 잔액에서 SUCCESS 거래내역 합계를 뺀 값과 같다")
    void mixedResults_balanceMatchesSuccessHistories() {
        Account account = saveAccount(INITIAL_BALANCE);

        // 1) 성공: 수수료 + 송금액 출금
        remittanceProcessorService.execute(commandFor(account));

        // 2) 롤백 실패: 수수료 출금 이후 예외
        failOnWithdrawalHistorySave();
        assertThatThrownBy(() -> remittanceProcessorService.execute(commandFor(account)))
                .isInstanceOf(IllegalStateException.class);

        // 3) 잔액 부족 실패: 남은 잔액보다 큰 금액 송금
        ExecuteRemittanceCommand tooLarge = commandFor(account, new BigDecimal("10000"));
        assertThatThrownBy(() -> remittanceProcessorService.execute(tooLarge))
                .isInstanceOf(CustomBaseException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorBaseCode.INSUFFICIENT_FUNDS);

        List<Transaction> histories = historiesOf(account);
        BigDecimal successTotal = histories.stream()
                .filter(history -> history.getTxnResult() == TxnResult.SUCCESS)
                .map(Transaction::getTxnAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL_BALANCE.subtract(successTotal));
        assertThat(histories).filteredOn(history -> history.getTxnResult() == TxnResult.SUCCESS).hasSize(2);
        assertThat(histories).filteredOn(history -> history.getTxnResult() == TxnResult.FAILURE).hasSize(1);
    }

    /**
     * 송금액(WITHDRAWAL) SUCCESS 내역을 저장하는 순간 예외를 던지게 합니다.
     * 이 시점에는 이미 수수료가 출금되고 수수료 SUCCESS 내역이 저장된 상태라,
     * 출금 이후 단계에서 트랜잭션이 롤백되는 상황을 재현할 수 있습니다.
     */
    private void failOnWithdrawalHistorySave() {
        willThrow(new IllegalStateException("출금 이후 단계 실패 재현"))
                .given(transactionService)
                .saveTransaction(any(), any(), eq(TxnType.WITHDRAWAL), any(), eq(TxnResult.SUCCESS));
    }

    private ExecuteRemittanceCommand commandFor(Account account) {
        return commandFor(account, SEND_AMOUNT_USD);
    }

    private ExecuteRemittanceCommand commandFor(Account account, BigDecimal targetAmountUsd) {
        return ExecuteRemittanceCommand.of(
                account.getUserId(),
                recipient.getRecipientId(),
                account.getId(),
                null,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                targetAmountUsd,
                LocalDate.now()
        );
    }

    private Account saveAccount(BigDecimal balance) {
        return accountRepository.save(Account.of(
                null, "encoded-password", "테스트 계좌",
                balance, AccountType.DEPOSIT, AccountState.ACTIVE, 1L
        ));
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private List<Transaction> historiesOf(Account account) {
        return transactionRepository.findByAccountIdWithAccount(account.getId());
    }

    private Recipient createRecipient() {
        try {
            Constructor<Recipient> constructor = Recipient.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Recipient newRecipient = constructor.newInstance();

            ReflectionTestUtils.setField(newRecipient, "name", "John Doe");
            ReflectionTestUtils.setField(newRecipient, "phoneNo", "310-555-1234");
            ReflectionTestUtils.setField(newRecipient, "phoneCc", "+1");
            ReflectionTestUtils.setField(newRecipient, "bankName", "Test Bank");
            ReflectionTestUtils.setField(newRecipient, "bankCode", "CHASUS33XXX");
            ReflectionTestUtils.setField(newRecipient, "accountNo", UUID.randomUUID().toString().substring(0, 12));
            ReflectionTestUtils.setField(newRecipient, "country", "USA");
            ReflectionTestUtils.setField(newRecipient, "currencyCode", CurrencyCode.USD);

            return newRecipient;
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 수취인 생성에 실패했습니다.", e);
        }
    }
}
