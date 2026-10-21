package org.creditto.core_banking.domain.regularremittance.repository;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import jakarta.persistence.LockModeType;
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
import java.time.LocalDate;

@Repository
public interface RegularRemittanceRepository extends JpaRepository<RegularRemittance,Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM RegularRemittance rr WHERE rr.regRemId = :id")
    Optional<RegularRemittance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 실패한 정기송금을 지연(DELAYED) 상태로 바꿉니다. 상태 컬럼만 변경합니다.
     * 사용자가 일시정지·취소한 정기송금이나, 그 사이 다른 실행에서 오늘 이미 성공한 정기송금은 바꾸지 않습니다.
     * (오늘 성공한 건을 DELAYED로 덮어쓰면 다음 평일 배치에서 다시 출금됨)
     *
     * @param id 정기송금 ID
     * @param today 배치 기준일
     * @return 변경된 행 수 (0이면 상태를 바꾸지 않음)
     */
    @Transactional
    @Modifying
    @Query("UPDATE RegularRemittance rr " +
            "SET rr.regRemStatus = org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.DELAYED " +
            "WHERE rr.regRemId = :id " +
            "AND rr.regRemStatus IN (" +
            "org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.ACTIVE, " +
            "org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.DELAYED) " +
            "AND (rr.lastExecutedDate IS NULL OR rr.lastExecutedDate <> :today)")
    int markDelayedIfNotExecuted(@Param("id") Long id, @Param("today") LocalDate today);

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
