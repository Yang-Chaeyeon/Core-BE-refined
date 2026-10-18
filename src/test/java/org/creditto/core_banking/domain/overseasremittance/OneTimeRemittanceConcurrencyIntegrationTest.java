package org.creditto.core_banking.domain.overseasremittance;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.exchange.dto.ExchangeRateRes;
import org.creditto.core_banking.domain.overseasremittance.dto.OverseasRemittanceRequestDto;
import org.creditto.core_banking.domain.overseasremittance.service.OneTimeRemittanceService;
import org.creditto.core_banking.domain.recipient.service.RecipientFactory;
import org.creditto.core_banking.domain.remittancefee.entity.FlatServiceFee;
import org.creditto.core_banking.domain.remittancefee.entity.NetworkFee;
import org.creditto.core_banking.domain.remittancefee.entity.PctServiceFee;
import org.creditto.core_banking.domain.remittancefee.repository.FlatServiceFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.NetworkFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.PctServiceFeeRepository;
import org.creditto.core_banking.domain.transaction.entity.Transaction;
import org.creditto.core_banking.domain.transaction.entity.TxnResult;
import org.creditto.core_banking.domain.transaction.repository.TransactionRepository;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.feign.ExchangeRateProvider;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 실제 MySQL · Redis에서 같은 계좌로 일회성 해외송금 2건이 동시에 들어올 때 잔액 정합성을 확인합니다.
 *
 * <p>성공한 거래내역(수수료 · 출금) 금액의 합만큼 잔액이 줄어들어야 합니다.
 * 한 요청이 다른 요청의 출금이 커밋되기 전에 계좌를 읽어두고 그 값으로 출금하면,
 * 거래내역은 2건 모두 남지만 잔액은 1건만큼만 줄어듭니다(lost update).</p>
 */
@DisplayName("[통합] 일회성 해외송금 동시 실행")
class OneTimeRemittanceConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final String PASSWORD = "1234";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal SEND_AMOUNT_USD = new BigDecimal("100");
    private static final Map<String, ExchangeRateRes> RATES = Map.of(
            "USD", ExchangeRateRes.builder()
                    .currencyUnit("USD")
                    .baseRate("1,400.00")
                    .currencyName("미국 달러")
                    .build()
    );

    @MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @MockitoSpyBean
    private RecipientFactory recipientFactory;

    @Autowired
    private OneTimeRemittanceService oneTimeRemittanceService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FlatServiceFeeRepository flatServiceFeeRepository;

    @Autowired
    private PctServiceFeeRepository pctServiceFeeRepository;

    @Autowired
    private NetworkFeeRepository networkFeeRepository;

    private Account account;

    @BeforeEach
    void setUp() {
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
                null, passwordEncoder.encode(PASSWORD), "동시성 테스트 계좌",
                INITIAL_BALANCE, AccountType.DEPOSIT, AccountState.ACTIVE, 1L
        ));
    }

    @Test
    @DisplayName("같은 계좌로 송금 2건이 동시에 실행돼도, 잔액은 성공한 거래내역 금액만큼 줄어든다")
    void concurrentRemittances_keepBalanceConsistent() throws Exception {
        // 두 번째 송금이 계좌를 읽은 뒤(수취인 조회 시점) 신호를 보냄
        // processRemittance는 계좌 조회 → 비밀번호 검증 → 수취인 조회 → 송금 실행 순서
        CountDownLatch secondReadAccount = new CountDownLatch(1);
        AtomicInteger recipientCalls = new AtomicInteger();
        willAnswer(invocation -> {
            Object recipient = invocation.callRealMethod();
            if (recipientCalls.incrementAndGet() == 2) {
                secondReadAccount.countDown();
            }
            return recipient;
        }).given(recipientFactory).findOrCreate(any());

        // 첫 번째 송금은 계좌 락을 잡은 채 환율 조회에서 멈춰, 두 번째 송금이 계좌를 읽을 때까지 커밋하지 않음
        // (첫 번째 송금이 두 번째 송금의 완료를 기다리면, 두 번째 송금은 계좌 락을 못 얻어 교착 상태가 되므로 '계좌를 읽은 시점'까지만 기다림)
        CountDownLatch firstInProgress = new CountDownLatch(1);
        AtomicInteger exchangeCalls = new AtomicInteger();
        given(exchangeRateProvider.getExchangeRates()).willAnswer(invocation -> {
            if (exchangeCalls.incrementAndGet() == 1) {
                firstInProgress.countDown();
                if (!secondReadAccount.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("두 번째 송금이 계좌를 읽지 못했습니다.");
                }
            }
            return RATES;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() ->
                    oneTimeRemittanceService.processRemittance(account.getUserId(), request()));

            assertThat(firstInProgress.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() ->
                    oneTimeRemittanceService.processRemittance(account.getUserId(), request()));

            // 두 송금 모두 예외 없이 성공해야 함 (실패하면 여기서 예외가 그대로 드러남)
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<Transaction> successes = transactionRepository.findByAccountIdWithAccount(account.getId()).stream()
                .filter(transaction -> transaction.getTxnResult() == TxnResult.SUCCESS)
                .toList();
        BigDecimal totalDeducted = successes.stream()
                .map(Transaction::getTxnAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 송금 1건당 수수료 + 출금 = 거래내역 2건
        assertThat(successes).hasSize(4);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL_BALANCE.subtract(totalDeducted));
    }

    private OverseasRemittanceRequestDto request() {
        return OverseasRemittanceRequestDto.builder()
                .accountNo(account.getAccountNo())
                .password(PASSWORD)
                .recipientInfo(OverseasRemittanceRequestDto.RecipientInfo.builder()
                        .name("John Doe")
                        // 두 요청이 수취인을 동시에 새로 만들 때 서로 부딪히지 않도록 계좌번호를 다르게 함
                        .accountNo(UUID.randomUUID().toString().substring(0, 12))
                        .bankName("Test Bank")
                        .bankCode("CHASUS33XXX")
                        .phoneCc("+1")
                        .phoneNo("310-555-1234")
                        .country("USA")
                        .receiveCurrency(CurrencyCode.USD)
                        .build())
                .recurId(null)
                .startDate(LocalDate.now())
                .sendCurrency(CurrencyCode.KRW)
                .targetAmount(SEND_AMOUNT_USD)
                .build();
    }

    private BigDecimal balanceOf(Account target) {
        return accountRepository.findById(target.getId()).orElseThrow().getBalance();
    }
}
