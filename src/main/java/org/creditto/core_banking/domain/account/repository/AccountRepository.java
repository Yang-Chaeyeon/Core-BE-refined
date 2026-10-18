package org.creditto.core_banking.domain.account.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.creditto.core_banking.domain.account.dto.AccountSummaryRes;
import org.creditto.core_banking.domain.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    // 계좌 번호로 계좌 조회
    Optional<Account> findByAccountNo(String accountNo);

    // 클라이언트 ID로 계좌 조회
    List<Account> findAccountByUserId(Long userId);

    @Query("SELECT a.balance FROM Account a WHERE a.id = :id")
    Optional<BigDecimal> findBalanceById(@Param("id") Long id);

    @Query("SELECT new org.creditto.core_banking.domain.account.dto.AccountSummaryRes( " +
            "COUNT(ac), COALESCE(SUM(ac.balance), 0)) " +
            "FROM Account ac WHERE ac.userId = :userId")
    AccountSummaryRes findAccountSummaryByUserId(@Param("userId") Long userId);

    // 락 대기 시간(3초)은 application.yml의 connection-init-sql로 적용됨
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
