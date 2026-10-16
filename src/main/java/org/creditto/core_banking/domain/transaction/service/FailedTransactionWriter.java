package org.creditto.core_banking.domain.transaction.service;

import lombok.RequiredArgsConstructor;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.transaction.entity.Transaction;
import org.creditto.core_banking.domain.transaction.entity.TxnResult;
import org.creditto.core_banking.domain.transaction.entity.TxnType;
import org.creditto.core_banking.domain.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 실패한 거래 내역을 별도 트랜잭션으로 저장합니다.
 *
 * <p>{@link TransactionService#recordFailure}가 원래 트랜잭션이 끝난 뒤(계좌 행 잠금이 풀린 뒤)에 호출합니다.
 * 원래 트랜잭션이 롤백되더라도 실패 기록은 남아야 하므로 REQUIRES_NEW로 분리합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class FailedTransactionWriter {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long accountId, BigDecimal amount, TxnType txnType, Long typeId) {
        Account account = accountRepository.getReferenceById(accountId);
        transactionRepository.save(Transaction.of(account, amount, txnType, typeId, TxnResult.FAILURE));
    }
}
