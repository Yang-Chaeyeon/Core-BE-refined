package org.creditto.core_banking.domain.account;

import org.assertj.core.api.Assertions;
import org.creditto.core_banking.domain.account.dto.AccountCreateReq;
import org.creditto.core_banking.domain.account.dto.AccountRes;
import org.creditto.core_banking.domain.account.dto.AccountSummaryRes;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.entity.AccountState;
import org.creditto.core_banking.domain.account.entity.AccountType;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.account.service.AccountLockService;
import org.creditto.core_banking.domain.account.service.AccountService;
import org.creditto.core_banking.domain.account.service.PasswordValidator;
import org.creditto.core_banking.domain.account.service.strategy.TransactionStrategy;
import org.creditto.core_banking.domain.account.service.strategy.TransactionStrategyFactory;
import org.creditto.core_banking.domain.transaction.entity.TxnType;
import org.creditto.core_banking.global.response.error.ErrorBaseCode;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionStrategyFactory strategyFactory;

    @Mock
    private PasswordValidator passwordValidator;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TransactionStrategy mockStrategy;

    @Mock
    private AccountLockService accountLockService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private AccountService accountService;

    @Test
    @DisplayName("계좌 생성 성공")
    void createAccount_Success() {
        // Given
        AccountCreateReq request = new AccountCreateReq(
                "새로운 계좌",
                AccountType.DEPOSIT,
                "1234"
                );
        Long userId = 1L;
        String rawPassword = "1234";
        String encodedPassword = "encoded_password";

        willDoNothing().given(passwordValidator).validatePassword(rawPassword);
        given(passwordEncoder.encode(rawPassword)).willReturn(encodedPassword);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);

        // 실제 save 메서드는 Account 객체를 받아 저장하고, 저장된 객체(ID가 부여된)를 반환해야 합니다.
        // 여기서는 captor가 캡처한 인스턴스를 그대로 반환하도록 설정하여 로직을 검증합니다.
        given(accountRepository.save(accountCaptor.capture())).willAnswer(invocation -> invocation.getArgument(0));


        // When
        accountService.createAccount(request, userId);

        // Then
        verify(passwordValidator).validatePassword(rawPassword);
        verify(passwordEncoder).encode(rawPassword);
        verify(accountRepository).save(any(Account.class));

        Account capturedAccount = accountCaptor.getValue();
        assertThat(capturedAccount.getPassword()).isEqualTo(encodedPassword);
        assertThat(capturedAccount.getAccountName()).isEqualTo(request.accountName());
        assertThat(capturedAccount.getAccountType()).isEqualTo(request.accountType());
        assertThat(capturedAccount.getAccountState()).isEqualTo(AccountState.ACTIVE);
        assertThat(capturedAccount.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("계좌 생성 실패 - 비밀번호 정책 위반")
    void createAccount_Failure_InvalidPasswordPolicy() {
        // Given
        AccountCreateReq request = new AccountCreateReq(
                "새로운 계좌",
                AccountType.DEPOSIT,
                "1111"
                );
        Long userId = 1L;

        doThrow(new CustomBaseException(ErrorBaseCode.PASSWORD_REPEATING_DIGITS))
                .when(passwordValidator).validatePassword(request.password());

        // When & Then
        assertThatThrownBy(() -> accountService.createAccount(request, userId))
                .isInstanceOf(CustomBaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorBaseCode.PASSWORD_REPEATING_DIGITS);

        verify(passwordValidator).validatePassword(request.password());
        verify(passwordEncoder, never()).encode(anyString());
        verify(accountRepository, never()).save(any(Account.class));
    }


    @Test
    @DisplayName("비밀번호 검증 성공")
    void verifyPassword_Success() {
        // Given
        Long accountId = 1L;
        String rawPassword = "1234";
        String encodedPassword = "encoded_password";
        Account mockAccount = Account.of("ACC001", encodedPassword, "테스트 계좌", BigDecimal.ZERO, AccountType.DEPOSIT, AccountState.ACTIVE, 1L);

        given(accountRepository.findById(accountId)).willReturn(Optional.of(mockAccount));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(true);

        // When & Then
        accountService.verifyPassword(accountId, rawPassword);
        // No exception thrown means success

        verify(accountRepository).findById(accountId);
        verify(passwordEncoder).matches(rawPassword, encodedPassword);
    }

    @Test
    @DisplayName("비밀번호 검증 실패 - 비밀번호 불일치")
    void verifyPassword_Failure_IncorrectPassword() {
        // Given
        Long accountId = 1L;
        String rawPassword = "wrong_password";
        String encodedPassword = "encoded_password";
        Account mockAccount = Account.of("ACC001", encodedPassword, "테스트 계좌", BigDecimal.ZERO, AccountType.DEPOSIT, AccountState.ACTIVE, 1L);

        given(accountRepository.findById(accountId)).willReturn(Optional.of(mockAccount));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(false);

        // When & Then
        assertThatThrownBy(() -> accountService.verifyPassword(accountId, rawPassword))
                .isInstanceOf(CustomBaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorBaseCode.INVALID_PASSWORD);

        verify(accountRepository).findById(accountId);
        verify(passwordEncoder).matches(rawPassword, encodedPassword);
    }

    @Test
    @DisplayName("비밀번호 검증 실패 - 계좌 없음")
    void verifyPassword_Failure_AccountNotFound() {
        // Given
        Long accountId = 999L;
        String rawPassword = "1234";

        given(accountRepository.findById(accountId)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> accountService.verifyPassword(accountId, rawPassword))
                .isInstanceOf(CustomBaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorBaseCode.NOT_FOUND_ACCOUNT);

        verify(accountRepository).findById(accountId);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }


    // --- 기존 테스트들 ---
    @Test
    @DisplayName("거래 처리 로직(processTransaction) 성공")
    void processTransaction_Success() {
        // given
        Long accountId = 1L;
        BigDecimal amount = new BigDecimal("10000");
        TxnType txnType = TxnType.WITHDRAWAL;
        Long relatedId = null;

        Account mockAccount = Account.of("ACC001", "테스트 계좌", "password", BigDecimal.valueOf(50000), AccountType.DEPOSIT, AccountState.ACTIVE, 1L);

        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(mockAccount));
        given(strategyFactory.getStrategy(txnType)).willReturn(mockStrategy);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return null;
        }).when(accountLockService).executeWithLock(eq(accountId), any(Runnable.class));

        // when
        accountService.processTransaction(accountId, amount, txnType, relatedId);

        // then
        verify(accountRepository).findByIdForUpdate(accountId);
        verify(strategyFactory).getStrategy(txnType);
        verify(mockStrategy).execute(mockAccount, amount, relatedId);
        verify(accountLockService).executeWithLock(eq(accountId), any(Runnable.class));
    }

    @Test
    @DisplayName("잔액 조회 성공")
    void getBalance_ById_Success() {
        // given
        Long accountId = 1L;
        Account mockAccount = Account.of("ACC001", "password", "테스트 계좌", BigDecimal.valueOf(100000), AccountType.DEPOSIT, AccountState.ACTIVE, 1L);

        given(accountRepository.findById(accountId)).willReturn(Optional.of(mockAccount));

        // when
        AccountRes result = accountService.getAccountById(accountId);

        // then
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.valueOf(100000));
    }

    @Test
    @DisplayName("존재하지 않는 계좌 번호로 조회 시 예외 발생")
    void getBalance_ById_AccountNotFound() {
        // given
        String invalidAccountNo = "ACC999";
        given(accountRepository.findByAccountNo(invalidAccountNo))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> accountService.getAccountByAccountNo(invalidAccountNo))
                .isInstanceOf(CustomBaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorBaseCode.NOT_FOUND_ACCOUNT);
    }


    @Test
    @DisplayName("사용자 ID로 계좌 조회 성공")
    void getAccountByUserId_Success() {
        // given
        Long userId = 1L;

        Account account1 = Account.of("ACC001", "pwd1", "테스트 계좌", BigDecimal.valueOf(100000), AccountType.DEPOSIT, AccountState.ACTIVE, userId);
        Account account2 = Account.of("ACC002", "pwd2", "적금계좌", BigDecimal.valueOf(50000), AccountType.SAVINGS, AccountState.ACTIVE, userId);

        given(accountRepository.findAccountByUserId(userId))
                .willReturn(List.of(account1, account2));

        // when
        List<AccountRes> result = accountService.getAccountByUserId(userId);

        // then
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).accountNo()).isEqualTo("ACC001");
        assertThat(result.get(1).accountName()).isEqualTo("적금계좌");
    }

    @Test
    @DisplayName("전체 잔액 합계 조회 테스트")
    void getTotalBalanceByUserId_returnsAggregateBalanceFromRepository() {
        Long userId = 7L;

        // 캐시 미스 상황: Redis에 값이 없어 DB에서 조회
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AccountSummaryRes expected = new AccountSummaryRes(2, BigDecimal.valueOf(50000));
        when(accountRepository.findAccountSummaryByUserId(userId)).thenReturn(expected);

        AccountSummaryRes result = accountService.getTotalBalanceByUserId(userId);
        BigDecimal totalBalance = result.totalBalance();
        long accountCount = result.accountCount();

        Assertions.assertThat(totalBalance).isEqualByComparingTo(BigDecimal.valueOf(50000));
        Assertions.assertThat(accountCount).isEqualTo(2);
        verify(accountRepository).findAccountSummaryByUserId(userId);
        verifyNoMoreInteractions(accountRepository);
        verifyNoInteractions(strategyFactory);
    }

    @Test
    @DisplayName("잔액이 0인 경우 0을 반환")
    void getTotalBalanceByUserId_returnsZeroWhenRepositoryReportsZero() {
        Long userId = 15L;
        // 캐시 미스 상황: Redis에 값이 없어 DB에서 조회
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(accountRepository.findAccountSummaryByUserId(userId)).thenReturn(new AccountSummaryRes(0, BigDecimal.ZERO));

        AccountSummaryRes res = accountService.getTotalBalanceByUserId(userId);

        Assertions.assertThat(res.totalBalance()).isEqualTo(BigDecimal.ZERO);
        Assertions.assertThat(res.accountCount()).isZero();
        verify(accountRepository).findAccountSummaryByUserId(userId);
        verifyNoMoreInteractions(accountRepository);
        verifyNoInteractions(strategyFactory);
    }
}
