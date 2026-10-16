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
import org.creditto.core_banking.domain.transaction.repository.TransactionRepository;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.feign.ExchangeRateProvider;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 실제 MySQL에서 해외송금 1건을 실행했을 때의 동작을 확인합니다.
 *
 * <p>RemittanceProcessorService.execute()는 계좌 행을 FOR UPDATE로 잠근 뒤,
 * TransactionService.saveTransaction()(REQUIRES_NEW)으로 거래내역을 별도 트랜잭션에서 저장합니다.
 * 거래내역 테이블은 account_id 외래키를 가지므로, InnoDB에서는 이 저장이 바깥 트랜잭션의 잠금을
 * 기다리게 되는지 검증합니다.</p>
 */
@DisplayName("[통합] 해외송금 1건 실행")
class RemittanceProcessorIntegrationTest extends IntegrationTestSupport {

    private static final Duration EXPECTED_MAX_DURATION = Duration.ofSeconds(5);

    @MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

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

    private Account account;
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

        account = accountRepository.save(Account.of(
                null, "encoded-password", "테스트 계좌",
                new BigDecimal("1000000"), AccountType.DEPOSIT, AccountState.ACTIVE, 1L
        ));
        recipient = recipientRepository.save(createRecipient());
    }

    @Test
    @DisplayName("송금 1건은 락 대기 없이 바로 끝나고, 거래내역(수수료·출금) 2건이 저장된다")
    void execute_completesWithoutLockWait() {
        ExecuteRemittanceCommand command = ExecuteRemittanceCommand.of(
                account.getUserId(),
                recipient.getRecipientId(),
                account.getId(),
                null,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                new BigDecimal("100"),
                LocalDate.now()
        );

        long start = System.nanoTime();
        remittanceProcessorService.execute(command);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(EXPECTED_MAX_DURATION);
        assertThat(transactionRepository.findByAccountIdWithAccount(account.getId())).hasSize(2);
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
