package org.creditto.core_banking.domain.regularremittance.repository;

import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface MonthlyRegularRemittanceRepository extends JpaRepository<MonthlyRegularRemittance, Long> {

    /**
     * 배치에서 실행할 월간 정기송금을 ID 순서로 조회합니다.
     * 오늘 예약된 ACTIVE 정기송금과, 예약일과 관계없이 지연(DELAYED)된 정기송금을 함께 가져옵니다.
     * 처리 중 상태가 바뀌어도 건너뛰는 건이 없도록, 페이지 번호 대신 마지막으로 처리한 ID 이후를 조회합니다.
     *
     * @param scheduledDates 오늘 처리할 예약일 목록 (월말에는 남은 날짜 포함)
     * @param lastId 이전 조회에서 마지막으로 가져온 정기송금 ID (처음에는 0)
     * @param pageable 한 번에 가져올 건수
     * @return 실행할 월간 정기송금 목록
     */
    @Query("SELECT m FROM MonthlyRegularRemittance m " +
            "JOIN FETCH m.account " +
            "JOIN FETCH m.recipient " +
            "WHERE m.regRemId > :lastId " +
            "AND (m.regRemStatus = org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.DELAYED " +
            "OR (m.scheduledDate IN :scheduledDates " +
            "AND m.regRemStatus = org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.ACTIVE)) " +
            "ORDER BY m.regRemId"
    )
    List<MonthlyRegularRemittance> findExecutableMonthlyRemittances(
            @Param("scheduledDates") Collection<Integer> scheduledDates,
            @Param("lastId") Long lastId,
            Pageable pageable
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MonthlyRegularRemittance m " +
            "SET m.regRemStatus = :toStatus " +
            "WHERE m.scheduledDate IN :scheduledDates " +
            "AND m.regRemStatus = :fromStatus"
    )
    int bulkUpdateRegRemStatusByScheduledDates(
            @Param("scheduledDates") Collection<Integer> scheduledDates,
            @Param("toStatus") RegRemStatus toStatus,
            @Param("fromStatus") RegRemStatus fromStatus
    );
}
