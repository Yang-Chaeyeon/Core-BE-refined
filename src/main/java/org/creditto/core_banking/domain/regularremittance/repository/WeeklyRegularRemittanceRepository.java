package org.creditto.core_banking.domain.regularremittance.repository;

import org.creditto.core_banking.domain.regularremittance.entity.WeeklyRegularRemittance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.DayOfWeek;
import java.util.List;

@Repository
public interface WeeklyRegularRemittanceRepository extends JpaRepository<WeeklyRegularRemittance, Long> {

    /**
     * 배치에서 실행할 주간 정기송금을 ID 순서로 조회합니다.
     * 오늘 요일로 예약된 ACTIVE 정기송금과, 요일과 관계없이 지연(DELAYED)된 정기송금을 함께 가져옵니다.
     * 처리 중 상태가 바뀌어도 건너뛰는 건이 없도록, 페이지 번호 대신 마지막으로 처리한 ID 이후를 조회합니다.
     *
     * @param scheduledDay 오늘 요일
     * @param lastId 이전 조회에서 마지막으로 가져온 정기송금 ID (처음에는 0)
     * @param pageable 한 번에 가져올 건수
     * @return 실행할 주간 정기송금 목록
     */
    @Query("SELECT w FROM WeeklyRegularRemittance w " +
            "JOIN FETCH w.account " +
            "JOIN FETCH w.recipient " +
            "WHERE w.regRemId > :lastId " +
            "AND (w.regRemStatus = org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.DELAYED " +
            "OR (w.scheduledDay = :scheduledDay " +
            "AND w.regRemStatus = org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus.ACTIVE)) " +
            "ORDER BY w.regRemId")
    List<WeeklyRegularRemittance> findExecutableWeeklyRemittances(
            @Param("scheduledDay") DayOfWeek scheduledDay,
            @Param("lastId") Long lastId,
            Pageable pageable
    );
}
