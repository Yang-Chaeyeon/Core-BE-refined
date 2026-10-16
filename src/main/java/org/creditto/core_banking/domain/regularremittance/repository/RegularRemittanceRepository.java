package org.creditto.core_banking.domain.regularremittance.repository;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import jakarta.persistence.LockModeType;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.creditto.core_banking.domain.regularremittance.entity.RegularRemittance;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import java.util.List;
import java.math.BigDecimal;
import java.time.DayOfWeek;

@Repository
public interface RegularRemittanceRepository extends JpaRepository<RegularRemittance,Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM RegularRemittance rr WHERE rr.regRemId = :id")
    Optional<RegularRemittance> findByIdForUpdate(@Param("id") Long id);

    // 상태 컬럼만 변경 (엔티티 전체를 저장하면 다른 트랜잭션이 기록한 lastExecutedDate를 덮어쓸 수 있음)
    @Transactional
    @Modifying
    @Query("UPDATE RegularRemittance rr SET rr.regRemStatus = :status WHERE rr.regRemId = :id")
    int updateRegRemStatus(@Param("id") Long id, @Param("status") RegRemStatus status);

    @Query("SELECT rr " +
            "FROM RegularRemittance rr " +
            "JOIN FETCH rr.account a " +
            "JOIN FETCH rr.recipient " +
            "WHERE a.userId = :userId")
    List<RegularRemittance> findByAccountUserId(@Param("userId") Long userId);

    @Query("""
            SELECT (COUNT(m) > 0)
            FROM MonthlyRegularRemittance m
            WHERE m.account = :account
              AND m.recipient = :recipient
              AND m.sendCurrency = :sendCurrency
              AND m.receivedCurrency = :receiveCurrency
              AND m.sendAmount = :sendAmount
              AND m.scheduledDate = :scheduledDate
            """)
    boolean existsMonthlyDuplicate(
            @Param("account") Account account,
            @Param("recipient") Recipient recipient,
            @Param("sendCurrency") CurrencyCode sendCurrency,
            @Param("receiveCurrency") CurrencyCode receiveCurrency,
            @Param("sendAmount") BigDecimal sendAmount,
            @Param("scheduledDate") Integer scheduledDate
    );

    @Query("""
            SELECT (COUNT(w) > 0)
            FROM WeeklyRegularRemittance w
            WHERE w.account = :account
              AND w.recipient = :recipient
              AND w.sendCurrency = :sendCurrency
              AND w.receivedCurrency = :receiveCurrency
              AND w.sendAmount = :sendAmount
              AND w.scheduledDay = :scheduledDay
            """)
    boolean existsWeeklyDuplicate(
            @Param("account") Account account,
            @Param("recipient") Recipient recipient,
            @Param("sendCurrency") CurrencyCode sendCurrency,
            @Param("receiveCurrency") CurrencyCode receiveCurrency,
            @Param("sendAmount") BigDecimal sendAmount,
            @Param("scheduledDay") DayOfWeek scheduledDay
    );
}
