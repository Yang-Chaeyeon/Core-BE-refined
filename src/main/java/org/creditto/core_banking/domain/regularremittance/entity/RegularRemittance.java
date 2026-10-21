package org.creditto.core_banking.domain.regularremittance.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.global.common.CurrencyCode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@SuperBuilder
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "regrem_type")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public abstract class RegularRemittance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long regRemId;

    @ManyToOne(fetch = FetchType.LAZY)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    private Recipient recipient;

    @Enumerated(EnumType.STRING)
    private CurrencyCode sendCurrency;

    @Enumerated(EnumType.STRING)
    private CurrencyCode receivedCurrency;

    private BigDecimal sendAmount;

    @Enumerated(EnumType.STRING)
    private RegRemStatus regRemStatus;

    // 송금 시작일
    private LocalDate startedAt;

    // 마지막으로 정기송금 배치가 실행된 날짜 (같은 날 중복 실행 방지)
    private LocalDate lastExecutedDate;

    // 엔티티 생성 시각 자동 저장
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    // 엔티티 수정 시각 자동 저장
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;



    public void updateRegRemStatus(RegRemStatus regRemStatus) {
        this.regRemStatus = regRemStatus;
    }

    public boolean isExecutedOn(LocalDate date) {
        return date != null && date.equals(this.lastExecutedDate);
    }

    /**
     * 정기송금 실행일을 기록하고, 지연(DELAYED) 상태였다면 ACTIVE로 되돌립니다.
     * 출금과 같은 트랜잭션에서 함께 저장되어야 하므로, 실행일 기록과 상태 복구를 한 메서드에서 처리합니다.
     * (상태 복구만 따로 저장하다 실패하면, 출금된 정기송금이 DELAYED로 남아 다음 배치에서 다시 출금될 수 있음)
     *
     * @param date 실행일 (배치 기준일)
     */
    public void markExecuted(LocalDate date) {
        this.lastExecutedDate = date;
        if (this.regRemStatus == RegRemStatus.DELAYED) {
            this.regRemStatus = RegRemStatus.ACTIVE;
        }
    }

    public void updateDetails(
            Account account,
            BigDecimal sendAmount,
            RegRemStatus regRemStatus
    ) {
        this.account = account;
        this.sendAmount = sendAmount;
        this.regRemStatus = regRemStatus;
    }
}
