package org.creditto.core_banking.domain.account;

import org.creditto.core_banking.domain.account.dto.AccountCreateReq;
import org.creditto.core_banking.domain.account.dto.AccountSummaryRes;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.account.service.AccountService;
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
import org.creditto.core_banking.global.common.CurrencyCode;
import org.creditto.core_banking.global.feign.ExchangeRateProvider;
import org.creditto.core_banking.global.util.CacheKeyUtil;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 총 잔액 조회 캐시가 실제 Redis에서 생성 · 사용 · 무효화되는지 확인합니다.
 *
 * <p>테스트마다 다른 userId를 써서 DB와 Redis에 남은 다른 테스트의 데이터와 섞이지 않게 합니다.</p>
 */
@DisplayName("[통합] 총 잔액 조회 Redis 캐시")
class AccountTotalBalanceCacheIntegrationTest extends IntegrationTestSupport {

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(System.currentTimeMillis());
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");

    @MockitoBean
    private ExchangeRateProvider exchangeRateProvider;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RemittanceProcessorService remittanceProcessorService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RecipientRepository recipientRepository;

    @Autowired
    private FlatServiceFeeRepository flatServiceFeeRepository;

    @Autowired
    private PctServiceFeeRepository pctServiceFeeRepository;

    @Autowired
    private NetworkFeeRepository networkFeeRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Long userId;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        userId = USER_ID_SEQ.incrementAndGet();
        cacheKey = CacheKeyUtil.getTotalBalanceKey(userId);
        redisTemplate.delete(cacheKey);
    }

    /**
     * 캐시가 없을 때 DB에서 합산한 값을 반환하고, 같은 값을 TTL과 함께 Redis에 저장하는지 확인합니다.
     */
    @Test
    @DisplayName("첫 조회는 DB에서 합산하고 결과를 TTL과 함께 캐싱한다")
    void firstCall_loadsFromDbAndCaches() {
        saveAccount(INITIAL_BALANCE);

        AccountSummaryRes result = accountService.getTotalBalanceByUserId(userId);

        assertThat(result.accountCount()).isEqualTo(1);
        assertThat(result.totalBalance()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isEqualTo(result);
        assertThat(redisTemplate.getExpire(cacheKey)).isPositive();
    }

    /**
     * 캐시가 있으면 DB를 다시 합산하지 않는지 확인합니다.
     * 캐시를 거치지 않고 리포지토리로 계좌를 추가한 뒤에도 이전 값이 나오면 캐시에서 읽은 것입니다.
     */
    @Test
    @DisplayName("캐시가 있으면 DB 대신 캐시 값을 반환한다")
    void secondCall_returnsCachedValue() {
        saveAccount(INITIAL_BALANCE);
        AccountSummaryRes first = accountService.getTotalBalanceByUserId(userId);

        saveAccount(INITIAL_BALANCE);
        AccountSummaryRes second = accountService.getTotalBalanceByUserId(userId);

        assertThat(second).isEqualTo(first);
        assertThat(second.accountCount()).isEqualTo(1);
    }

    /**
     * 계좌를 새로 만들면 캐시가 지워져, 다음 조회에서 새 계좌가 포함된 값이 나오는지 확인합니다.
     */
    @Test
    @DisplayName("계좌를 생성하면 캐시가 삭제되고 다음 조회에 반영된다")
    void createAccount_evictsCache() {
        saveAccount(INITIAL_BALANCE);
        accountService.getTotalBalanceByUserId(userId);

        accountService.createAccount(new AccountCreateReq("새 계좌", AccountType.DEPOSIT, "1357"), userId);

        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();
        assertThat(accountService.getTotalBalanceByUserId(userId).accountCount()).isEqualTo(2);
    }

    /**
     * 해외송금으로 잔액이 줄면 캐시가 지워져, 다음 조회에서 줄어든 잔액이 나오는지 확인합니다.
     */
    @Test
    @DisplayName("해외송금으로 잔액이 바뀌면 캐시가 삭제되고 다음 조회에 반영된다")
    void remittance_evictsCache() {
        Account account = saveAccount(INITIAL_BALANCE);
        Recipient recipient = prepareRemittance();
        accountService.getTotalBalanceByUserId(userId);

        remittanceProcessorService.execute(ExecuteRemittanceCommand.of(
                userId,
                recipient.getRecipientId(),
                account.getId(),
                null,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                new BigDecimal("100"),
                LocalDate.now()
        ));

        BigDecimal balanceInDb = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();
        assertThat(accountService.getTotalBalanceByUserId(userId).totalBalance())
                .isEqualByComparingTo(balanceInDb)
                .isLessThan(INITIAL_BALANCE);
    }

    /**
     * 송금에 필요한 고정 환율, 수수료 정책, 수취인을 준비합니다.
     *
     * @return 저장된 수취인
     */
    private Recipient prepareRemittance() {
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

        return recipientRepository.save(createRecipient());
    }

    private Account saveAccount(BigDecimal balance) {
        return accountRepository.save(Account.of(
                null, "encoded-password", "테스트 계좌",
                balance, AccountType.DEPOSIT, AccountState.ACTIVE, userId
        ));
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
