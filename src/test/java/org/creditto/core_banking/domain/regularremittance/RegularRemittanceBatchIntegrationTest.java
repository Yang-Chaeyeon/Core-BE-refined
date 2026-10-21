package org.creditto.core_banking.domain.regularremittance;

import org.creditto.core_banking.common.vo.Money;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.exchange.dto.ExchangeRateRes;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.recipient.repository.RecipientRepository;
import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.creditto.core_banking.domain.overseasremittance.dto.ExecuteRemittanceCommand;
import org.creditto.core_banking.domain.overseasremittance.service.RemittanceProcessorService;
import org.creditto.core_banking.domain.regularremittance.repository.MonthlyRegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.repository.RegularRemittanceRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * 실제 MySQL · Redis에서 월간 정기송금 배치를 실행해 트랜잭션 경계와 락 범위를 검증합니다.
 *
 * <p>테스트마다 다른 배치 날짜(예약일)를 사용해, 다른 테스트가 만든 정기송금이 함께 처리되지 않게 합니다.</p>
 */
@DisplayName("[통합] 월간 정기송금 배치")
class RegularRemittanceBatchIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
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

    @Autowired
    private RegularRemittanceRepository regularRemittanceRepository;

    @Autowired
    private RemittanceProcessorService remittanceProcessorService;

    private Recipient recipient;

    @BeforeEach
    void setUp() {
        given(exchangeRateProvider.getExchangeRates()).willReturn(RATES);

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

    @Test
    @DisplayName("잔액 부족으로 1건이 실패해도 나머지 정기송금은 출금된다")
    void oneFailure_doesNotRollbackOthers() {
        LocalDate batchDate = LocalDate.of(2026, 10, 13); // 화요일

        Account first = saveAccount(INITIAL_BALANCE);
        Account poor = saveAccount(BigDecimal.ZERO);
        Account last = saveAccount(INITIAL_BALANCE);

        saveMonthlyRemittance(first, batchDate);
        MonthlyRegularRemittance failing = saveMonthlyRemittance(poor, batchDate);
        saveMonthlyRemittance(last, batchDate);

        assertThatCode(() -> runMonthlyBatch(batchDate)).doesNotThrowAnyException();

        assertThat(balanceOf(first)).isLessThan(INITIAL_BALANCE);
        assertThat(balanceOf(last)).isLessThan(INITIAL_BALANCE);
        assertThat(balanceOf(poor)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(statusOf(failing)).isEqualTo(RegRemStatus.DELAYED);
    }

    @Test
    @DisplayName("배치가 다음 건을 처리하는 동안, 이미 처리한 계좌의 잠금은 바로 얻을 수 있다")
    void processedAccount_isNotLockedUntilBatchEnds() throws Exception {
        LocalDate batchDate = LocalDate.of(2026, 10, 14); // 수요일

        // 같은 계좌로 정기송금 3건: 1건 처리 후 다음 건을 처리하는 동안 계좌 잠금 여부를 확인
        Account target = saveAccount(INITIAL_BALANCE);
        saveMonthlyRemittance(target, batchDate);
        saveMonthlyRemittance(target, batchDate);
        saveMonthlyRemittance(target, batchDate);

        // 2번째 건부터 환율 조회를 2초 지연시켜, 배치가 다음 건을 처리 중인 상태를 만든다
        CountDownLatch firstItemDone = new CountDownLatch(1);
        AtomicInteger exchangeCalls = new AtomicInteger();
        given(exchangeRateProvider.getExchangeRates()).willAnswer(invocation -> {
            if (exchangeCalls.incrementAndGet() >= 2) {
                firstItemDone.countDown();
                Thread.sleep(2000);
            }
            return RATES;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Duration> lockWait = executor.submit(() -> {
                if (!firstItemDone.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("배치가 첫 번째 건을 끝내지 못했습니다.");
                }
                long start = System.nanoTime();
                transactionTemplate.executeWithoutResult(status ->
                        accountRepository.findByIdForUpdate(target.getId()));
                return Duration.ofNanos(System.nanoTime() - start);
            });

            runMonthlyBatch(batchDate);

            assertThat(lockWait.get(60, TimeUnit.SECONDS)).isLessThan(Duration.ofSeconds(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 날 배치를 다시 실행해도 정기송금이 중복 출금되지 않는다")
    void rerunOnSameDay_doesNotWithdrawTwice() {
        LocalDate batchDate = LocalDate.of(2026, 10, 15); // 목요일

        Account account = saveAccount(INITIAL_BALANCE);
        saveMonthlyRemittance(account, batchDate);

        runMonthlyBatch(batchDate);
        BigDecimal balanceAfterFirstRun = balanceOf(account);

        runMonthlyBatch(batchDate);

        assertThat(balanceAfterFirstRun).isLessThan(INITIAL_BALANCE);
        assertThat(balanceOf(account)).isEqualByComparingTo(balanceAfterFirstRun);
    }

    @Test
    @DisplayName("예상치 못한 예외로 1건이 실패해도 나머지 정기송금은 실행된다")
    void unexpectedException_doesNotStopBatch() {
        LocalDate batchDate = LocalDate.of(2026, 10, 16); // 금요일

        List<Account> accounts = List.of(
                saveAccount(INITIAL_BALANCE),
                saveAccount(INITIAL_BALANCE),
                saveAccount(INITIAL_BALANCE)
        );
        List<MonthlyRegularRemittance> remittances = accounts.stream()
                .map(account -> saveMonthlyRemittance(account, batchDate))
                .toList();

        // 2번째 건의 환율 조회만 실패시켜, 외부 환율 API가 일시적으로 에러를 낸 상황을 만든다
        AtomicInteger exchangeCalls = new AtomicInteger();
        given(exchangeRateProvider.getExchangeRates()).willAnswer(invocation -> {
            if (exchangeCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("환율 API 일시 오류");
            }
            return RATES;
        });

        assertThatCode(() -> runMonthlyBatch(batchDate)).doesNotThrowAnyException();

        // 어느 건이 2번째로 처리될지는 조회 순서에 따르므로, 건수와 함께 건별로 출금 여부와 상태를 짝지어 확인
        long withdrawnCount = accounts.stream()
                .filter(account -> balanceOf(account).compareTo(INITIAL_BALANCE) < 0)
                .count();
        assertThat(withdrawnCount).isEqualTo(2);

        for (int i = 0; i < accounts.size(); i++) {
            boolean withdrawn = balanceOf(accounts.get(i)).compareTo(INITIAL_BALANCE) < 0;
            RegRemStatus expected = withdrawn ? RegRemStatus.ACTIVE : RegRemStatus.DELAYED;
            assertThat(statusOf(remittances.get(i))).isEqualTo(expected);
        }
    }

    /**
     * 주말에 예약돼 DELAYED로 미뤄진 월간 정기송금이 다음 평일(월요일) 배치에서 실행되는지 확인합니다.
     */
    @Test
    @DisplayName("주말이라 지연된 정기송금은 다음 평일 배치에서 실행된다")
    void weekendDelayed_isExecutedOnNextWeekday() {
        LocalDate saturday = LocalDate.of(2026, 10, 17);
        LocalDate monday = LocalDate.of(2026, 10, 19);

        Account account = saveAccount(INITIAL_BALANCE);
        MonthlyRegularRemittance remittance = saveMonthlyRemittance(account, saturday);

        runMonthlyBatch(saturday);

        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.DELAYED);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL_BALANCE);

        runMonthlyBatch(monday);

        assertThat(balanceOf(account)).isLessThan(INITIAL_BALANCE);
        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.ACTIVE);
    }

    /**
     * 잔액 부족으로 실패해 DELAYED가 된 월간 정기송금이 다음 날 배치에서 다시 실행되는지 확인합니다.
     */
    @Test
    @DisplayName("실패로 지연된 정기송금은 다음 날 배치에서 다시 실행된다")
    void failedDelayed_isRetriedNextDay() {
        LocalDate tuesday = LocalDate.of(2026, 10, 20);
        LocalDate wednesday = LocalDate.of(2026, 10, 21);

        Account account = saveAccount(BigDecimal.ZERO);
        MonthlyRegularRemittance remittance = saveMonthlyRemittance(account, tuesday);

        runMonthlyBatch(tuesday);

        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.DELAYED);

        deposit(account, INITIAL_BALANCE);
        runMonthlyBatch(wednesday);

        assertThat(balanceOf(account)).isLessThan(INITIAL_BALANCE);
        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.ACTIVE);
    }

    /**
     * 지연(DELAYED) 정기송금이 성공하면, 출금과 같은 트랜잭션 안에서 ACTIVE로 복구되는지 확인합니다.
     * 스케줄러가 별도로 상태를 바꾸지 않아도 복구되어야, 상태 저장 실패로 DELAYED가 남아 다음 날 중복 출금되는 일이 없습니다.
     */
    @Test
    @DisplayName("지연된 정기송금이 성공하면 출금과 같은 트랜잭션에서 ACTIVE로 복구된다")
    void delayedRemittance_isRestoredToActiveWithinSameTransaction() {
        LocalDate thursday = LocalDate.of(2026, 10, 22);

        Account account = saveAccount(INITIAL_BALANCE);
        MonthlyRegularRemittance remittance = saveMonthlyRemittance(account, thursday);
        regularRemittanceRepository.markDelayedIfNotExecuted(remittance.getRegRemId(), thursday);

        // 스케줄러를 거치지 않고 송금만 실행
        remittanceProcessorService.executeScheduled(ExecuteRemittanceCommand.of(remittance, thursday));

        assertThat(balanceOf(account)).isLessThan(INITIAL_BALANCE);
        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.ACTIVE);
    }

    /**
     * 오늘 이미 성공한 정기송금은, 겹쳐 실행된 다른 배치의 실패 처리로 DELAYED가 되지 않는지 확인합니다.
     * DELAYED로 덮어써지면 다음 평일 배치에서 다시 출금되기 때문입니다.
     */
    @Test
    @DisplayName("오늘 이미 실행된 정기송금은 실패 처리로 DELAYED가 되지 않는다")
    void executedToday_isNotMarkedDelayed() {
        LocalDate friday = LocalDate.of(2026, 10, 23);

        Account account = saveAccount(INITIAL_BALANCE);
        MonthlyRegularRemittance remittance = saveMonthlyRemittance(account, friday);
        remittanceProcessorService.executeScheduled(ExecuteRemittanceCommand.of(remittance, friday));

        int updated = regularRemittanceRepository.markDelayedIfNotExecuted(remittance.getRegRemId(), friday);

        assertThat(updated).isZero();
        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.ACTIVE);
    }

    /**
     * 사용자가 일시정지한 정기송금은 배치의 실패 처리로 DELAYED가 되지 않는지 확인합니다.
     * DELAYED로 덮어써지면 사용자가 멈춘 송금이 다음 평일 배치에서 출금되기 때문입니다.
     */
    @Test
    @DisplayName("일시정지된 정기송금은 실패 처리로 DELAYED가 되지 않는다")
    void paused_isNotMarkedDelayed() {
        LocalDate friday = LocalDate.of(2026, 10, 23);

        Account account = saveAccount(INITIAL_BALANCE);
        MonthlyRegularRemittance remittance = saveMonthlyRemittance(account, friday);
        transactionTemplate.executeWithoutResult(status ->
                monthlyRegularRemittanceRepository.findById(remittance.getRegRemId()).orElseThrow()
                        .updateRegRemStatus(RegRemStatus.PAUSED)
        );

        int updated = regularRemittanceRepository.markDelayedIfNotExecuted(remittance.getRegRemId(), friday);

        assertThat(updated).isZero();
        assertThat(statusOf(remittance)).isEqualTo(RegRemStatus.PAUSED);
    }

    private void deposit(Account account, BigDecimal amount) {
        transactionTemplate.executeWithoutResult(status ->
                accountRepository.findById(account.getId()).orElseThrow()
                        .deposit(Money.of(amount, CurrencyCode.KRW))
        );
    }

    private void runMonthlyBatch(LocalDate batchDate) {
        try (MockedStatic<LocalDate> mockedLocalDate = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(batchDate);
            scheduler.executeMonthlyRegularRemittance();
        }
    }

    private Account saveAccount(BigDecimal balance) {
        return accountRepository.save(Account.of(
                null, "encoded-password", "테스트 계좌",
                balance, AccountType.DEPOSIT, AccountState.ACTIVE, 1L
        ));
    }

    private MonthlyRegularRemittance saveMonthlyRemittance(Account account, LocalDate batchDate) {
        return monthlyRegularRemittanceRepository.save(MonthlyRegularRemittance.of(
                account,
                recipient,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                SEND_AMOUNT_USD,
                batchDate.getDayOfMonth(),
                batchDate.minusMonths(1)
        ));
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private RegRemStatus statusOf(MonthlyRegularRemittance remittance) {
        return monthlyRegularRemittanceRepository.findById(remittance.getRegRemId()).orElseThrow().getRegRemStatus();
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
