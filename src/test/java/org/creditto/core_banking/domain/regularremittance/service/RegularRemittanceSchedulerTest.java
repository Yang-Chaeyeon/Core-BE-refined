package org.creditto.core_banking.domain.regularremittance.service;

import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.overseasremittance.dto.ExecuteRemittanceCommand;
import org.creditto.core_banking.domain.overseasremittance.service.RemittanceProcessorService;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.creditto.core_banking.domain.regularremittance.entity.WeeklyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.repository.MonthlyRegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.repository.RegularRemittanceRepository;
import org.creditto.core_banking.domain.regularremittance.repository.WeeklyRegularRemittanceRepository;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.lang.reflect.Constructor;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.test.util.ReflectionTestUtils;

import static org.creditto.core_banking.domain.account.entity.AccountState.ACTIVE;
import static org.creditto.core_banking.domain.account.entity.AccountType.DEPOSIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegularRemittanceSchedulerTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final int CHUNK_SIZE = 1000;

    @Mock
    private RemittanceProcessorService remittanceProcessorService;
    @Mock
    private MonthlyRegularRemittanceRepository monthlyRegularRemittanceRepository;
    @Mock
    private WeeklyRegularRemittanceRepository weeklyRegularRemittanceRepository;
    @Mock
    private RegularRemittanceRepository regularRemittanceRepository;

    @InjectMocks
    private RegularRemittanceScheduler scheduler;

    private Account account;
    private Recipient recipient;

    @BeforeEach
    void setUp() {
        Long userId = 1L;
        account = Account.of("1002-123-456789", "1057", "예금계좌", BigDecimal.valueOf(1_000_000), DEPOSIT, ACTIVE, userId);
        ReflectionTestUtils.setField(account, "id", 1L); // ID 설정

        recipient = createRecipient();
        ReflectionTestUtils.setField(recipient, "recipientId", 1L); // ID 설정
    }

    @Test
    @DisplayName("월간 정기송금 - 평일에 마지막 ID 이후를 이어서 조회하며 ACTIVE/DELAYED 송금 수행")
    void executeMonthlyRegularRemittance_weekdayProcessesActiveAndDelayed() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 10);
        int scheduledDate = fixedDate.getDayOfMonth();

        // 첫 조회는 1000건(가득 참) → 마지막 ID(1000) 이후로 한 번 더 조회
        List<MonthlyRegularRemittance> firstChunk = createMonthlyRemittances(CHUNK_SIZE, 1L, scheduledDate);
        firstChunk.get(1).updateRegRemStatus(RegRemStatus.DELAYED);
        List<MonthlyRegularRemittance> secondChunk = createMonthlyRemittances(1, CHUNK_SIZE + 1L, scheduledDate);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findExecutableMonthlyRemittances(
                    eq(List.of(scheduledDate)), eq(0L), any(Pageable.class)
            )).willReturn(firstChunk);
            given(monthlyRegularRemittanceRepository.findExecutableMonthlyRemittances(
                    eq(List.of(scheduledDate)), eq((long) CHUNK_SIZE), any(Pageable.class)
            )).willReturn(secondChunk);

            scheduler.executeMonthlyRegularRemittance();
        }

        verify(remittanceProcessorService, times(CHUNK_SIZE + 1)).executeScheduled(any(ExecuteRemittanceCommand.class));
        // 성공 시 상태 복구는 송금 트랜잭션 안에서 처리하므로, 스케줄러는 상태를 따로 바꾸지 않음
        verify(regularRemittanceRepository, never()).markDelayedIfNotExecuted(any(), any());
    }

    @Test
    @DisplayName("월간 정기송금 - 주말에는 DELAYED로 상태 변경")
    void executeMonthlyRegularRemittance_weekendMarksAsDelayed() {
        LocalDate fixedDate = LocalDate.of(2024, 6, 8); // Saturday
        int scheduledDate = fixedDate.getDayOfMonth();

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.bulkUpdateRegRemStatusByScheduledDates(
                    eq(List.of(scheduledDate)),
                    eq(RegRemStatus.DELAYED),
                    eq(RegRemStatus.ACTIVE)
            )).willReturn(2);

            scheduler.executeMonthlyRegularRemittance();
        }

        verify(monthlyRegularRemittanceRepository)
                .bulkUpdateRegRemStatusByScheduledDates(eq(List.of(scheduledDate)), eq(RegRemStatus.DELAYED), eq(RegRemStatus.ACTIVE));
        verify(monthlyRegularRemittanceRepository, never()).findExecutableMonthlyRemittances(any(), any(), any());
        verify(remittanceProcessorService, never()).executeScheduled(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("월간 정기송금 - 월말에는 남은 날짜 포함하여 조회")
    void executeMonthlyRegularRemittance_endOfMonthCoversRemainingDates() {
        LocalDate fixedDate = LocalDate.of(2024, 4, 30);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findExecutableMonthlyRemittances(
                    anyCollection(), eq(0L), any(Pageable.class)
            )).willReturn(List.of());

            scheduler.executeMonthlyRegularRemittance();
        }

        ArgumentCaptor<Collection<Integer>> scheduledCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(monthlyRegularRemittanceRepository)
                .findExecutableMonthlyRemittances(scheduledCaptor.capture(), eq(0L), any(Pageable.class));

        List<Integer> capturedDates = new ArrayList<>(scheduledCaptor.getValue());
        assertEquals(List.of(30, 31), capturedDates);
    }

    @Test
    @DisplayName("월간 정기송금 스케쥴링 - 120건 수행")
    void executeMonthlyRegularRemittance_handlesMoreThanHundredItems() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 15);
        int scheduledDate = fixedDate.getDayOfMonth();

        List<MonthlyRegularRemittance> remittances = createMonthlyRemittances(120, 1L, scheduledDate);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findExecutableMonthlyRemittances(
                    eq(List.of(scheduledDate)), eq(0L), any(Pageable.class)
            )).willReturn(remittances);

            scheduler.executeMonthlyRegularRemittance();
        }

        // 1000건 미만이면 한 번만 조회
        verify(monthlyRegularRemittanceRepository, times(1)).findExecutableMonthlyRemittances(any(), any(), any());
        verify(remittanceProcessorService, times(remittances.size())).executeScheduled(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("주간 정기송금 - 마지막 ID 이후를 이어서 조회하며 모두 수행")
    void executeWeeklyRegularRemittance_processesAllChunks() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 8);
        DayOfWeek scheduledDay = fixedDate.getDayOfWeek();

        List<WeeklyRegularRemittance> firstChunk = createWeeklyRemittances(CHUNK_SIZE, 1L, scheduledDay);
        List<WeeklyRegularRemittance> secondChunk = createWeeklyRemittances(1, CHUNK_SIZE + 1L, scheduledDay);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(weeklyRegularRemittanceRepository.findExecutableWeeklyRemittances(
                    eq(scheduledDay), eq(0L), any(Pageable.class)
            )).willReturn(firstChunk);
            given(weeklyRegularRemittanceRepository.findExecutableWeeklyRemittances(
                    eq(scheduledDay), eq((long) CHUNK_SIZE), any(Pageable.class)
            )).willReturn(secondChunk);

            scheduler.executeWeeklyRegularRemittance();
        }

        verify(remittanceProcessorService, times(CHUNK_SIZE + 1)).executeScheduled(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("주간 정기송금 스케쥴링 - 120건 수행")
    void executeWeeklyRegularRemittance_handlesMoreThanHundredItems() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 15);
        DayOfWeek scheduledDay = fixedDate.getDayOfWeek();

        List<WeeklyRegularRemittance> remittances = createWeeklyRemittances(120, 1L, scheduledDay);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(weeklyRegularRemittanceRepository.findExecutableWeeklyRemittances(
                    eq(scheduledDay), eq(0L), any(Pageable.class)
            )).willReturn(remittances);

            scheduler.executeWeeklyRegularRemittance();
        }

        verify(remittanceProcessorService, times(remittances.size())).executeScheduled(any(ExecuteRemittanceCommand.class));
    }

    private List<MonthlyRegularRemittance> createMonthlyRemittances(int count, long startId, int scheduledDate) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    MonthlyRegularRemittance remittance = MonthlyRegularRemittance.of(
                            account, recipient, CurrencyCode.KRW, CurrencyCode.USD,
                            BigDecimal.valueOf(1000), scheduledDate, LocalDate.of(2024, 1, 1)
                    );
                    ReflectionTestUtils.setField(remittance, "regRemId", startId + i);
                    return remittance;
                })
                .toList();
    }

    private List<WeeklyRegularRemittance> createWeeklyRemittances(int count, long startId, DayOfWeek scheduledDay) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    WeeklyRegularRemittance remittance = WeeklyRegularRemittance.of(
                            account, recipient, CurrencyCode.KRW, CurrencyCode.USD,
                            BigDecimal.valueOf(3000), scheduledDay, LocalDate.of(2024, 1, 1)
                    );
                    ReflectionTestUtils.setField(remittance, "regRemId", startId + i);
                    return remittance;
                })
                .toList();
    }

    private Recipient createRecipient() {
        try {
            Constructor<Recipient> constructor = Recipient.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Recipient recipient = constructor.newInstance();

            ReflectionTestUtils.setField(recipient, "name", "John Doe");
            ReflectionTestUtils.setField(recipient, "phoneNo", "310-555-1234");
            ReflectionTestUtils.setField(recipient, "phoneCc", "+1");
            ReflectionTestUtils.setField(recipient, "bankName", "Test Bank");
            ReflectionTestUtils.setField(recipient, "bankCode", "CHASUS33XXX");
            ReflectionTestUtils.setField(recipient, "accountNo", "1234567890");
            ReflectionTestUtils.setField(recipient, "country", "USA");
            ReflectionTestUtils.setField(recipient, "currencyCode", CurrencyCode.USD);

            return recipient;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create Recipient for tests", e);
        }
    }
}
