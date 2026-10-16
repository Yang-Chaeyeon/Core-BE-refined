package org.creditto.core_banking.domain.regularremittance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.creditto.core_banking.domain.overseasremittance.dto.ExecuteRemittanceCommand;
import org.creditto.core_banking.domain.overseasremittance.service.RemittanceProcessorService;
import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.creditto.core_banking.domain.regularremittance.entity.RegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.WeeklyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.repository.MonthlyRegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.repository.RegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.repository.WeeklyRegularRemittanceRepository;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegularRemittanceScheduler {

    private final RemittanceProcessorService remittanceProcessorService;
    private final MonthlyRegularRemittanceRepository monthlyRegularRemittanceRepository;
    private final WeeklyRegularRemittanceRepository weeklyRegularRemittanceRepository;
    private final RegularRemittanceRepository regularRemittanceRepository;

    private static final int SIZE = 1000;
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    // 배치 전체를 하나의 트랜잭션으로 묶지 않음: 정기송금 1건마다 RemittanceProcessorService.execute()가 트랜잭션을 열고 커밋
    @Scheduled(cron = "${scheduler.remittance.monthly-cron}")
    public void executeMonthlyRegularRemittance() {
        LocalDate now = LocalDate.now(ZONE_ID);
        int nowDayOfMonth = now.getDayOfMonth();

        log.info("[RegularRemittanceScheduler {}/{}/{}] 월간 정기 해외송금 Job Start",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth()
        );

        // 월말이 31일 아닌 경우, 이후에 예약된 정기 송금들에 대해서도 처리
        List<Integer> scheduledDates = (nowDayOfMonth == now.lengthOfMonth() && nowDayOfMonth < 31)
                ? IntStream.rangeClosed(nowDayOfMonth, 31)
                    .boxed()
                    .toList()
                : List.of(nowDayOfMonth);

        int page = 0;
        long total = 0L;

        Page<MonthlyRegularRemittance> slice;

        // 정기 송금일이 주말일 경우 RegRem Status를 DELAYED로 업데이트
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            int updatedCount = monthlyRegularRemittanceRepository.bulkUpdateRegRemStatusByScheduledDates(
                    scheduledDates,
                    RegRemStatus.DELAYED,
                    RegRemStatus.ACTIVE
            );
            total += updatedCount;
            log.info("[RegularRemittanceScheduler {}/{}/{}] 월간 정기 해외송금 Job : 주말로 인한 송금 지연, 지연된 정기 송금 수 = {}",
                    now.getYear(), now.getMonthValue(), now.getDayOfMonth(), total
            );
            return;
        }

        do {

            // 평일의 경우 DELAYED & ACTIVE 정기송금을 실행되게 함
            slice = monthlyRegularRemittanceRepository
                    .findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                            scheduledDates,
                            Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED),
                            PageRequest.of(page, SIZE)
                    );

            executeRemittanceForRegRemList(slice.getContent());

            total += slice.getNumberOfElements();
            page++;
        } while (slice.hasNext());

        log.info("[RegularRemittanceScheduler {}/{}/{}] 월간 정기 해외송금 Job : 수행한 송금 수 = {}",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), total
        );
    }

    @Scheduled(cron = "${scheduler.remittance.weekly-cron}")
    public void executeWeeklyRegularRemittance() {
        LocalDate now = LocalDate.now(ZONE_ID);
        DayOfWeek dayOfWeek = now.getDayOfWeek();

        log.info("[RegularRemittanceScheduler {}/{}/{}] 주간 정기 해외송금 Job Start",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth()
        );

        int page = 0;
        long total = 0L;

        Page<WeeklyRegularRemittance> slice;

        do {
            slice = weeklyRegularRemittanceRepository
                    .findWeeklyRegularRemittanceByScheduledDayAndRegRemStatusIn(
                            dayOfWeek,
                            Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED),
                            PageRequest.of(page, SIZE)
                    );

            executeRemittanceForRegRemList(slice.getContent());

            total += slice.getNumberOfElements();
            page++;
        } while (slice.hasNext());

        log.info("[RegularRemittanceScheduler {}/{}/{}] 주간 정기 해외송금 Job : 수행한 송금 수 = {}",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), total
        );
    }

    private void executeRemittanceForRegRemList(List<? extends RegularRemittance> remittances) {
        remittances.forEach(remittance -> {
            ExecuteRemittanceCommand remittanceCommand = ExecuteRemittanceCommand.of(remittance);
            try {
                remittanceProcessorService.execute(remittanceCommand);
                // 연기된 작업 수행 후 ACTIVE로 수정
                if (remittance.getRegRemStatus().equals(RegRemStatus.DELAYED)) {
                    remittance.updateRegRemStatus(RegRemStatus.ACTIVE);
                    regularRemittanceRepository.save(remittance);
                }
            } catch (CustomBaseException e) {
                log.error("[RegularRemittanceScheduler] 정기 송금 실행에 실패하였습니다. remittanceId={}, error={}", remittance.getRegRemId(), e.getMessage());
                remittance.updateRegRemStatus(RegRemStatus.DELAYED);
                regularRemittanceRepository.save(remittance);
            }
        });
    }
}
