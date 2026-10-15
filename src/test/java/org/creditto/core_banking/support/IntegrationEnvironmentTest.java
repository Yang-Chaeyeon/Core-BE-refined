package org.creditto.core_banking.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("통합 테스트 환경 (MySQL · Redis 컨테이너)")
class IntegrationEnvironmentTest extends IntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("H2가 아닌 MySQL 8.0(InnoDB)에 연결된다")
    void connectsToMySqlWithInnoDb() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        String engine = jdbcTemplate.queryForObject("SELECT @@default_storage_engine", String.class);

        assertThat(version).startsWith("8.0");
        assertThat(engine).isEqualToIgnoringCase("InnoDB");
    }

    @Test
    @DisplayName("Redis 컨테이너에서 Redisson 분산 락을 획득하고 해제할 수 있다")
    void acquiresAndReleasesRedissonLock() throws InterruptedException {
        RLock lock = redissonClient.getLock("integration:environment:lock");

        boolean acquired = lock.tryLock(1, 5, TimeUnit.SECONDS);
        try {
            assertThat(acquired).isTrue();
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }

        assertThat(lock.isLocked()).isFalse();
    }
}
