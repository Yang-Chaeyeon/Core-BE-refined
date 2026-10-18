package org.creditto.core_banking.domain.account.service;

import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.creditto.core_banking.global.response.error.ErrorBaseCode;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLockService {

    private final RedissonClient redissonClient;
    private final AccountLockProperties accountLockProperties;

    public <T> T executeWithLock(Long accountId, LockCallback<T> callback) {
        RLock lock = redissonClient.getLock(accountLockProperties.getAccountLockPrefix() + accountId);
        boolean redisLockAcquired = false;
        boolean redisAvailable = true;

        try {
            redisLockAcquired = lock.tryLock(
                    accountLockProperties.getWaitMillis(),
                    accountLockProperties.getLeaseMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RedissonShutdownException redisException) {
            redisAvailable = false;
            log.warn("Redis lock 불가, fallback 전략을 사용합니다. accountId={}, reason={}", accountId, redisException.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomBaseException(ErrorBaseCode.ACCOUNT_LOCK_INTERRUPTED);
        }

        try {
            if (!redisAvailable) {
                // Redis 사용 불가 & DB 분산락만 적용
                return callback.invokeFallback();
            }

            if (!redisLockAcquired) {
                // Lock 획득 실패
                throw new CustomBaseException(ErrorBaseCode.ACCOUNT_LOCK_TIMEOUT);
            }

            return callback.invoke();

        } catch (InterruptedException e) {
            // Interrupt 관련 에러
            Thread.currentThread().interrupt();
            throw new CustomBaseException(ErrorBaseCode.ACCOUNT_LOCK_INTERRUPTED);
        } finally {
            // isHeldByCurrentThread()도 Redis 조회라 예외가 날 수 있으므로, 커밋 이후 예외가 호출자에게 전파되지 않게 함께 감쌈
            if (redisLockAcquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException unlockException) {
                    log.warn("Redis lock 해제 실패. accountId={}, reason={}", accountId, unlockException.getMessage());
                }
            }
        }
    }

    public void executeWithLock(Long accountId, Runnable runnable) {
        executeWithLock(accountId, new LockCallback<Void>() {
            @Override
            public Void invoke() {
                runnable.run();
                return null;
            }

            @Override
            public Void invokeFallback() {
                runnable.run();
                return null;
            }
        });
    }

    public interface LockCallback<T> {
        T invoke() throws InterruptedException;

        default T invokeFallback() throws InterruptedException {
            return invoke();
        }
    }
}
