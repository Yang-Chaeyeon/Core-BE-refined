package org.creditto.core_banking.domain.regularremittance;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.exchange.dto.ExchangeRateRes;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.recipient.repository.RecipientRepository;
import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.repository.MonthlyRegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.service.RegularRemittanceScheduler;
import org.creditto.core_banking.domain.remittancefee.entity.FlatServiceFee;
import org.creditto.core_banking.domain.remittancefee.entity.NetworkFee;
import org.creditto.core_banking.domain.remittancefee.entity.PctServiceFee;
import org.creditto.core_banking.domain.remittancefee.repository.FlatServiceFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.NetworkFeeRepository;
import org.creditto.core_banking.domain.remittancefee.repository.PctServiceFeeRepository;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.feign.ExchangeRateProvider;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * 정기송금 배치를 실제 규모(1000건)로 실행해, 배치 도중 처리가 끝난 계좌를 사용자가 얼마나 기다려야 쓸 수 있는지 측정합니다.
 *
 * <p>배치 전체가 하나의 트랜잭션이면 처리한 계좌의 행 잠금이 배치가 끝날 때까지 유지되고,
 * 송금 건별 트랜잭션이면 해당 건이 커밋되는 즉시 풀립니다. 이 차이를 인위적인 지연 없이 측정합니다.</p>
 *
 * <p>실행 시간이 길어 {@code @Tag("performance")}로 분리했고, {@code ./gradlew performanceTest}로만 실행됩니다.</p>
 */
@Tag("performance")
@DisplayName("[성능] 정기송금 1000건 배치 중 계좌 잠금 대기 시간")
class RegularRemittanceBatchPerformanceTest extends IntegrationTestSupport {

    private static final int REMITTANCE_COUNT = 1000;
    private static final LocalDate BATCH_DATE = LocalDate.of(2026, 11, 3); // 화요일, 다른 테스트와 겹치지 않는 예약일
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal SEND_AMOUNT_USD = new BigDecimal("100");
    private static final Duration EXPECTED_MAX_LOCK_WAIT = Duration.ofSeconds(1);
    private static final Map<String, ExchangeRateRes> RATES = Map.of(
            "USD", ExchangeRateRes.builder()
                    .currencyUnit("USD")
                    .baseRate("1,400.00")
                    .currencyName("미국 달러")
                    .build()
    );

    @MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @Autowired
    private RegularRemittanceScheduler scheduler;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RecipientRepository recipientRepository;

    @Autowired
    private MonthlyRegularRemittanceRepository monthlyRegularRemittanceRepository;

    @Autowired
    private FlatServiceFeeRepository flatServiceFeeRepository;

    @Autowired
    private PctServiceFeeRepository pctServiceFeeRepository;

    @Autowired
    private NetworkFeeRepository networkFeeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Recipient recipient;

    // 측정 스레드가 기록하고 테스트 스레드가 읽으므로 volatile
    private volatile Duration lockWait;
    private volatile String lockFailure;

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

        recipient = recipientRepository.save(createRecipient());
    }

    /**
     * 1000건 배치에서 첫 번째 송금이 끝난 직후, 그 계좌에 사용자 요청처럼 잠금을 요청해 대기 시간을 측정합니다.
     * 측정 결과는 표준 출력에 남기고, 잠금을 1초 안에 얻는지 검증합니다.
     */
    @Test
    @DisplayName("1000건 배치 중에도 처리가 끝난 계좌의 잠금은 1초 안에 얻을 수 있다")
    void processedAccount_isAvailableDuringLargeBatch() throws Exception {
        Account firstAccount = saveAccountsAndRemittances();

        // 2번째 송금의 환율 조회가 시작되면 = 1번째 송금 처리가 끝난 시점
        CountDownLatch firstItemDone = new CountDownLatch(1);
        AtomicInteger exchangeCalls = new AtomicInteger();
        given(exchangeRateProvider.getExchangeRates()).willAnswer(invocation -> {
            if (exchangeCalls.incrementAndGet() == 2) {
                firstItemDone.countDown();
            }
            return RATES;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BigDecimal> balanceSeenByUser = executor.submit(() -> {
                if (!firstItemDone.await(5, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("배치가 첫 번째 건을 끝내지 못했습니다.");
                }
                long start = System.nanoTime();
                try {
                    BigDecimal balance = transactionTemplate.execute(status ->
                            accountRepository.findByIdForUpdate(firstAccount.getId()).orElseThrow().getBalance());
                    lockWait = Duration.ofNanos(System.nanoTime() - start);
                    return balance;
                } catch (RuntimeException e) {
                    lockWait = Duration.ofNanos(System.nanoTime() - start);
                    lockFailure = e.getClass().getSimpleName();
                    throw e;
                }
            });

            long batchStart = System.nanoTime();
            runMonthlyBatch();
            Duration batchDuration = Duration.ofNanos(System.nanoTime() - batchStart);

            BigDecimal balance = null;
            try {
                balance = balanceSeenByUser.get(5, TimeUnit.MINUTES);
            } catch (ExecutionException ignored) {
                // 잠금 대기 초과 등으로 실패한 경우에도 측정값은 아래에서 출력한다
            }

            System.out.printf("%n[성능 측정] 정기송금 %d건 배치%n", REMITTANCE_COUNT);
            System.out.printf("[성능 측정] 배치 전체 시간: %,d ms%n", batchDuration.toMillis());
            System.out.printf("[성능 측정] 처리 끝난 계좌 잠금 대기: %,d ms%s%n",
                    lockWait.toMillis(), lockFailure == null ? "" : " (" + lockFailure + "로 실패)");

            assertThat(lockFailure).as("처리 끝난 계좌의 잠금 요청이 실패했습니다.").isNull();
            // 잔액이 이미 줄어 있어야 '처리가 끝난 계좌'를 측정한 것이다
            assertThat(balance).isLessThan(INITIAL_BALANCE);
            assertThat(lockWait).isLessThan(EXPECTED_MAX_LOCK_WAIT);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 계좌 1000개와 계좌마다 정기송금 1건을 저장하고, 가장 먼저 처리될(가장 먼저 저장한) 계좌를 반환합니다.
     */
    private Account saveAccountsAndRemittances() {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < REMITTANCE_COUNT; i++) {
            accounts.add(Account.of(null, "encoded-password", "성능 측정 계좌",
                    INITIAL_BALANCE, AccountType.DEPOSIT, AccountState.ACTIVE, 1L));
        }
        List<Account> savedAccounts = accountRepository.saveAll(accounts);

        List<MonthlyRegularRemittance> remittances = new ArrayList<>();
        for (Account account : savedAccounts) {
            remittances.add(MonthlyRegularRemittance.of(
                    account,
                    recipient,
                    CurrencyCode.KRW,
                    CurrencyCode.USD,
                    SEND_AMOUNT_USD,
                    BATCH_DATE.getDayOfMonth(),
                    BATCH_DATE.minusMonths(1)
            ));
        }
        monthlyRegularRemittanceRepository.saveAll(remittances);

        return savedAccounts.get(0);
    }

    private void runMonthlyBatch() {
        try (MockedStatic<LocalDate> mockedLocalDate = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(BATCH_DATE);
            scheduler.executeMonthlyRegularRemittance();
        }
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
