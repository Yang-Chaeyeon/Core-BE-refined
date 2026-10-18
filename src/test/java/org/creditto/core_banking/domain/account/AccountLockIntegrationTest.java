package org.creditto.core_banking.domain.account;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 실제 MySQL에서 계좌 비관적 락(FOR UPDATE)의 대기 시간이 설정대로 적용되는지 확인합니다.
 *
 * <p>계좌 락 대기 시간은 3초로 설정되어 있습니다(application.yml의 connection-init-sql).
 * 다른 트랜잭션이 같은 계좌를 잠그고 있을 때, 두 번째 요청이 3초 안팎에서 포기하고
 * 락 예외를 던지는지 검증합니다.</p>
 */
@DisplayName("[통합] 계좌 락")
class AccountLockIntegrationTest extends IntegrationTestSupport {

    // 첫 번째 트랜잭션이 락을 붙잡고 있는 시간 (설정한 대기 시간 3초보다 충분히 길게)
    private static final Duration LOCK_HOLD_DURATION = Duration.ofSeconds(10);

    // 대기 시간 3초가 적용됐다면 이 시간 안에 포기해야 함
    private static final Duration EXPECTED_MAX_WAIT = Duration.ofSeconds(5);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Account account;

    @BeforeEach
    void setUp() {
        account = accountRepository.save(Account.of(
                null, "encoded-password", "락 테스트 계좌",
                new BigDecimal("1000000"), AccountType.DEPOSIT, AccountState.ACTIVE, 1L
        ));
    }

    @Test
    @DisplayName("다른 트랜잭션이 계좌를 잠그고 있으면, 설정한 대기 시간 안에 락 예외로 끝난다")
    void findByIdForUpdate_failsFastWhenLocked() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);

        // 스레드 A: 계좌를 잠근 채로 일정 시간 붙잡고 있음
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    accountRepository.findByIdForUpdate(account.getId()).orElseThrow();
                    locked.countDown();
                    sleep(LOCK_HOLD_DURATION);
                })
        );

        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            // 스레드 B(현재 스레드): 같은 계좌를 잠그려고 시도
            long start = System.nanoTime();
            Throwable thrown = catchThrowable(() ->
                    transactionTemplate.executeWithoutResult(status ->
                            accountRepository.findByIdForUpdate(account.getId()).orElseThrow()
                    )
            );
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isLessThan(EXPECTED_MAX_WAIT);
            assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            holder.get(LOCK_HOLD_DURATION.plusSeconds(10).toSeconds(), TimeUnit.SECONDS);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
