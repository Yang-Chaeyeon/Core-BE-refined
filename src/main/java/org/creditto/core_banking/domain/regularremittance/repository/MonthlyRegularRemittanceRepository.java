package org.creditto.core_banking.domain.regularremittance.repository;

import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@Repository
public interface MonthlyRegularRemittanceRepository extends JpaRepository<MonthlyRegularRemittance, Long> {

    @Query("SELECT m FROM MonthlyRegularRemittance m " +
            "JOIN FETCH m.account " +
            "JOIN FETCH m.recipient " +
            "WHERE m.scheduledDate IN :scheduledDates " +
            "AND m.regRemStatus IN :regRemStatuses"
    )
    Page<MonthlyRegularRemittance> findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
            @Param("scheduledDates") Collection<Integer> scheduledDates,
            @Param("regRemStatuses") Collection<RegRemStatus> regRemStatuses,
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
