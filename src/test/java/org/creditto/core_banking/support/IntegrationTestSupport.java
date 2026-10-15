package org.creditto.core_banking.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL · Redis 컨테이너 위에서 동작하는 통합 테스트의 공통 베이스 클래스입니다.
 *
 * <p>H2(MODE=MySQL)로는 InnoDB 락, 트랜잭션 전파, Redisson 분산 락의 실제 동작을 검증할 수 없기 때문에
 * 트랜잭션 · 락 · 캐시 관련 검증은 이 클래스를 상속해 작성합니다.</p>
 *
 * <p>컨테이너는 JVM당 한 번만 띄우고 모든 통합 테스트 클래스가 재사용합니다.
 * {@code @Tag("integration")}이 붙어 있어 {@code ./gradlew integrationTest}로만 실행됩니다.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public abstract class IntegrationTestSupport {

    private static final int REDIS_PORT = 6379;

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("core_banking")
            .withUsername("test")
            .withPassword("test");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideConnectionProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }
}
