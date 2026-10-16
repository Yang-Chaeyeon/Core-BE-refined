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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.lang.reflect.Constructor;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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
    @DisplayName("월간 정기송금 - 평일에 ACTIVE/DELAYED 송금 수행")
    void executeMonthlyRegularRemittance_weekdayProcessesActiveAndDelayed() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 10);
        int scheduledDate = fixedDate.getDayOfMonth();

        MonthlyRegularRemittance activeRemittance = createMonthlyRemittance(scheduledDate, RegRemStatus.ACTIVE);
        MonthlyRegularRemittance delayedRemittance = createMonthlyRemittance(scheduledDate, RegRemStatus.DELAYED);
        MonthlyRegularRemittance secondPageRemittance = createMonthlyRemittance(scheduledDate, RegRemStatus.ACTIVE);

        List<MonthlyRegularRemittance> page0Content = List.of(activeRemittance, delayedRemittance);
        List<MonthlyRegularRemittance> page1Content = List.of(secondPageRemittance);

        Page<MonthlyRegularRemittance> page0 = new PageImpl<>(page0Content, PageRequest.of(0, 1000), 1500);
        Page<MonthlyRegularRemittance> page1 = new PageImpl<>(page1Content, PageRequest.of(1, 1000), 1500);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                    eq(List.of(scheduledDate)),
                    eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                    any(PageRequest.class)
            )).willReturn(page0, page1);

            scheduler.executeMonthlyRegularRemittance();
        }

        verify(monthlyRegularRemittanceRepository, times(2))
                .findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                        eq(List.of(scheduledDate)),
                        eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                        any(PageRequest.class)
                );
        verify(remittanceProcessorService, times(3)).execute(any(ExecuteRemittanceCommand.class));
        assertEquals(RegRemStatus.ACTIVE, delayedRemittance.getRegRemStatus());
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
        verify(remittanceProcessorService, never()).execute(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("월간 정기송금 - 월말에는 남은 날짜 포함하여 조회")
    void executeMonthlyRegularRemittance_endOfMonthCoversRemainingDates() {
        LocalDate fixedDate = LocalDate.of(2024, 4, 30);

        Page<MonthlyRegularRemittance> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 1000), 0);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                    anyCollection(),
                    eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                    any(PageRequest.class)
            )).willReturn(emptyPage);

            scheduler.executeMonthlyRegularRemittance();
        }

        ArgumentCaptor<Collection<Integer>> scheduledCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(monthlyRegularRemittanceRepository)
                .findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                        scheduledCaptor.capture(),
                        eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                        any(PageRequest.class)
                );

        List<Integer> capturedDates = new ArrayList<>(scheduledCaptor.getValue());
        assertEquals(List.of(30, 31), capturedDates);
    }

    @Test
    @DisplayName("월간 정기송금 스케쥴링 - 120건 수행")
    void executeMonthlyRegularRemittance_handlesMoreThanHundredItems() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 15);
        LocalDate newStartedAt = LocalDate.of(2024, 1, 1);
        int scheduledDate = fixedDate.getDayOfMonth();

        List<MonthlyRegularRemittance> remittances = IntStream.range(0, 120)
                .mapToObj(i -> MonthlyRegularRemittance.of(account, recipient, CurrencyCode.KRW, CurrencyCode.USD, BigDecimal.valueOf(1000), scheduledDate, newStartedAt))
                .toList();

        Page<MonthlyRegularRemittance> page = new PageImpl<>(remittances, PageRequest.of(0, 1000), remittances.size());

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(monthlyRegularRemittanceRepository.findMonthlyRegularRemittanceByScheduledDateInAndRegRemStatusIn(
                    eq(List.of(scheduledDate)),
                    eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                    any(PageRequest.class)
            )).willReturn(page);

            scheduler.executeMonthlyRegularRemittance();
        }

        verify(remittanceProcessorService, times(remittances.size())).execute(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("주간 정기송금 스케줄러 수행")
    void executeWeeklyRegularRemittance_processesAllPages() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 8);
        DayOfWeek scheduledDay = fixedDate.getDayOfWeek();

        WeeklyRegularRemittance w1 = createWeeklyRemittance(scheduledDay);
        WeeklyRegularRemittance w2 = createWeeklyRemittance(scheduledDay);
        WeeklyRegularRemittance w3 = createWeeklyRemittance(scheduledDay);

        List<WeeklyRegularRemittance> page0Content = List.of(w1, w2);
        List<WeeklyRegularRemittance> page1Content = List.of(w3);

        Page<WeeklyRegularRemittance> page0 = new PageImpl<>(page0Content, PageRequest.of(0, 1000), 1500);
        Page<WeeklyRegularRemittance> page1 = new PageImpl<>(page1Content, PageRequest.of(1, 1000), 1500);

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(weeklyRegularRemittanceRepository.findWeeklyRegularRemittanceByScheduledDayAndRegRemStatusIn(
                    eq(scheduledDay),
                    eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                    any(PageRequest.class)
            )).willReturn(page0, page1);

            scheduler.executeWeeklyRegularRemittance();
        }

        verify(remittanceProcessorService, times(page0Content.size() + page1Content.size())).execute(any(ExecuteRemittanceCommand.class));
    }

    @Test
    @DisplayName("주간 정기송금 스케쥴링 - 120건 수행")
    void executeWeeklyRegularRemittance_handlesMoreThanHundredItems() {
        LocalDate fixedDate = LocalDate.of(2024, 7, 15);
        LocalDate newStartedAt = LocalDate.of(2024, 1, 1);
        DayOfWeek scheduledDay = fixedDate.getDayOfWeek();

        List<WeeklyRegularRemittance> remittances = IntStream.range(0, 120)
                .mapToObj(i -> WeeklyRegularRemittance.of(account, recipient, CurrencyCode.KRW, CurrencyCode.USD, BigDecimal.valueOf(3000), scheduledDay, newStartedAt))
                .toList();

        Page<WeeklyRegularRemittance> page = new PageImpl<>(remittances, PageRequest.of(0, 1000), remittances.size());

        try (MockedStatic<LocalDate> mockedLocalDate = Mockito.mockStatic(LocalDate.class)) {
            mockedLocalDate.when(() -> LocalDate.now(ZONE_ID)).thenReturn(fixedDate);

            given(weeklyRegularRemittanceRepository.findWeeklyRegularRemittanceByScheduledDayAndRegRemStatusIn(
                    eq(scheduledDay),
                    eq(Set.of(RegRemStatus.ACTIVE, RegRemStatus.DELAYED)),
                    any(PageRequest.class)
            )).willReturn(page);

            scheduler.executeWeeklyRegularRemittance();
        }

        verify(remittanceProcessorService, times(remittances.size())).execute(any(ExecuteRemittanceCommand.class));
    }

    private MonthlyRegularRemittance createMonthlyRemittance(int scheduledDate, RegRemStatus status) {
        MonthlyRegularRemittance remittance = MonthlyRegularRemittance.of(
                account,
                recipient,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                BigDecimal.valueOf(1000),
                scheduledDate,
                LocalDate.of(2024, 1, 1)

        );
        remittance.updateRegRemStatus(status);
        return remittance;
    }

    private WeeklyRegularRemittance createWeeklyRemittance(DayOfWeek scheduledDay) {
        return WeeklyRegularRemittance.of(
                account,
                recipient,
                CurrencyCode.KRW,
                CurrencyCode.USD,
                BigDecimal.valueOf(3000),
                scheduledDay,
                LocalDate.of(2024, 1, 1)
        );
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