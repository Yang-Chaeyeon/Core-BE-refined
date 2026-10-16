package org.creditto.core_banking.domain.transaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.transaction.dto.TransactionRes;
import org.creditto.core_banking.domain.transaction.entity.Transaction;
import org.creditto.core_banking.domain.transaction.entity.TxnResult;
import org.creditto.core_banking.domain.transaction.entity.TxnType;
import org.creditto.core_banking.domain.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final FailedTransactionWriter failedTransactionWriter;

    @Transactional(readOnly = true)
    public List<TransactionRes> findByAccountId(Long accountId) {
        List<Transaction> transactions = transactionRepository.findByAccountIdWithAccount(accountId);

        return transactions.stream()
                .map(TransactionRes::from)
                .toList();
    }

    /**
     * 거래 내역을 데이터베이스에 저장
     * 해당 메서드는 각 거래 전략에서 호출
     * 
     * @param account 거래가 발생한 계좌
     * @param amount 거래 금액
     * @param txnType 거래 유형
     * @param typeId 거래 관련 ID
     * @param txnResult 거래 결과
     * @return 저장된 거래 정보 DTO
     */

    // 잔액 변경과 같은 트랜잭션에서 저장해, 잔액과 거래 내역이 함께 커밋되거나 함께 롤백되도록 함
    // (별도 트랜잭션으로 저장하면 계좌 행을 FOR UPDATE로 잠근 호출자를 외래키 검사가 기다리며 락 대기가 발생)
    @Transactional
    public TransactionRes saveTransaction(Account account, BigDecimal amount, TxnType txnType, Long typeId, TxnResult txnResult) {
        // 새로운 Transaction 엔티티 생성
        Transaction transaction = Transaction.of(
                account,
                amount,
                txnType,
                typeId,
                txnResult
        );

        Transaction savedTransaction = transactionRepository.save(transaction);

        return TransactionRes.from(savedTransaction);
    }

    /**
     * 실패한 거래 내역을 기록
     * 호출한 트랜잭션이 끝난 뒤(계좌 행 잠금이 풀린 뒤) 별도 트랜잭션으로 저장하므로,
     * 호출한 트랜잭션이 롤백되더라도 실패 기록은 남음
     *
     * @param account 거래가 발생한 계좌
     * @param amount 거래 금액
     * @param txnType 거래 유형
     * @param typeId 거래 관련 ID
     */
    public void recordFailure(Account account, BigDecimal amount, TxnType txnType, Long typeId) {
        Long accountId = account.getId();

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            failedTransactionWriter.write(accountId, amount, txnType, typeId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    failedTransactionWriter.write(accountId, amount, txnType, typeId);
                } catch (RuntimeException e) {
                    log.error("[TransactionService] 실패 거래 내역 저장에 실패했습니다. accountId={}, txnType={}", accountId, txnType, e);
                }
            }
        });
    }
}
