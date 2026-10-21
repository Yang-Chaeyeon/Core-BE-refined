package org.creditto.core_banking.domain.regularremittance.service;

import lombok.RequiredArgsConstructor;
import org.creditto.core_banking.domain.account.entity.Account;
import org.creditto.core_banking.domain.account.repository.AccountRepository;
import org.creditto.core_banking.domain.overseasremittance.entity.OverseasRemittance;
import org.creditto.core_banking.domain.overseasremittance.repository.OverseasRemittanceRepository;
import org.creditto.core_banking.domain.recipient.dto.RecipientCreateDto;
import org.creditto.core_banking.domain.recipient.entity.Recipient;
import org.creditto.core_banking.domain.recipient.service.RecipientFactory;
import org.creditto.core_banking.domain.regularremittance.dto.*;
import org.creditto.core_banking.domain.regularremittance.entity.MonthlyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.RegRemStatus;
import org.creditto.core_banking.domain.regularremittance.entity.RegularRemittance;
import org.creditto.core_banking.domain.regularremittance.entity.WeeklyRegularRemittance;
import org.creditto.core_banking.domain.regularremittance.repository.RegularRemittanceRepository;
import org.creditto.core_banking.global.response.error.ErrorBaseCode;
import org.creditto.core_banking.global.response.exception.CustomBaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegularRemittanceService {

    private final RegularRemittanceRepository regularRemittanceRepository;
    private final OverseasRemittanceRepository overseasRemittanceRepository;
    private final AccountRepository accountRepository;
    private final RecipientFactory recipientFactory;

    /**
     * 특정 사용자의 모든 정기송금 설정 내역을 조회합니다.
     *
     * @param userId 사용자의 ID
     * @return 해당 사용자의 모든 정기송금 설정 목록 ({@link RegularRemittanceResponseDto})
     */
    public List<RegularRemittanceResponseDto> getScheduledRemittancesByUserId(Long userId) {
        List<RegularRemittance> remittances = regularRemittanceRepository.findByAccountUserId(userId);
        return remittances.stream()
                .map(RegularRemittanceResponseDto::from)
                .toList();
    }

    /**
     * 특정 정기송금의 세부사항을 조회합니다.
     *
     * @param userId 사용자의 ID
     * @param regRemId 정기송금 ID
     * @return 해당 정기송금 설정의 세부 사항 목록
     */
    public RemittanceDetailDto getScheduledRemittanceDetail(Long userId, Long regRemId) {
        RegularRemittance remittance = regularRemittanceRepository.findById(regRemId)
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_REGULAR_REMITTANCE));

        verifyUserOwnership(remittance.getAccount().getUserId(), userId);

        Recipient recipient = remittance.getRecipient();
        String regRemType = null;
        Integer scheduledDate = null;
        DayOfWeek scheduledDay = null;

        if (remittance instanceof MonthlyRegularRemittance monthly) {
            regRemType = "MONTHLY";
            scheduledDate = monthly.getScheduledDate();
        } else if (remittance instanceof WeeklyRegularRemittance weekly) {
            regRemType = "WEEKLY";
            scheduledDay = weekly.getScheduledDay();
        }
        return RemittanceDetailDto.builder()
                .accountNo(remittance.getAccount().getAccountNo())
                .sendAmount(remittance.getSendAmount())
                .regRemType(regRemType)
                .scheduledDate(scheduledDate)
                .scheduledDay(scheduledDay)
                .startedAt(remittance.getStartedAt())
                .sendCurrency(remittance.getSendCurrency())
                .recipientCountry(recipient.getCountry())
                .recipientBankName(recipient.getBankName())
                .recipientAccountNo(recipient.getAccountNo())
                .receiveCurrency(remittance.getReceivedCurrency())
                .recipientName(recipient.getName())
                .recipientPhoneCc(recipient.getPhoneCc())
                .recipientPhoneNo(recipient.getPhoneNo())
                .regRemStatus(remittance.getRegRemStatus())
                .build();
    }

    /**
     * 특정 정기송금 설정에 대한 모든 송금 기록을 조회합니다.
     *
     * @param userId   사용자의 ID
     * @param regRemId 조회할 정기송금의 ID
     * @return 해당 정기송금 설정에 대한 모든 송금 기록 목록 ({@link RemittanceHistoryDto})
     */
    public List<RemittanceHistoryDto> getRegularRemittanceHistoryByRegRemId(Long userId, Long regRemId) {
        RegularRemittance regularRemittance = regularRemittanceRepository.findById(regRemId)
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_REGULAR_REMITTANCE));

        verifyUserOwnership(regularRemittance.getAccount().getUserId(), userId);

        return overseasRemittanceRepository.findByRecur_RegRemIdOrderByCreatedAtDesc(regRemId).stream()
                .map(overseas -> new RemittanceHistoryDto(
                        overseas.getRemittanceId(),
                        overseas.getSendAmount(),
                        overseas.getExchange().getExchangeRate(),
                        overseas.getCreatedAt().toLocalDate()
                ))
                .toList();
    }

    /**
     * 단일 정기송금 내역의 상세 정보를 조회합니다.
     *
     * @param userId       사용자의 ID
     * @param remittanceId 조회할 송금의 ID
     * @return 해당 송금의 상세 정보 ({@link RemittanceHistoryDetailDto})
     */
    public RemittanceHistoryDetailDto getRemittanceHistoryDetail(Long userId, Long remittanceId, Long regRemId) {
        OverseasRemittance overseasRemittance = overseasRemittanceRepository.findByIdAndRecur_RegRemId(remittanceId, regRemId)
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_ENTITY));
        verifyUserOwnership(overseasRemittance.getUserId(), userId);

        return RemittanceHistoryDetailDto.builder()
                .accountNo(overseasRemittance.getAccount().getAccountNo())
                .totalFee(overseasRemittance.getFeeRecord().getTotalFee())
                .sendAmount(overseasRemittance.getSendAmount())
                .recipientBankName(overseasRemittance.getRecipient().getBankName())
                .recipientAccountNo(overseasRemittance.getRecipient().getAccountNo())
                .remittanceStatus(overseasRemittance.getRemittanceStatus())
                .build();
    }

    /**
     * 신규 정기송금을 등록합니다.
     *
     * @param userId 사용자의 ID
     * @param dto    정기송금 생성에 필요한 정보를 담은 DTO
     * @return 생성된 정기송금 정보 ({@link RegularRemittanceResponseDto})
     */
    @Transactional
    public RegularRemittanceResponseDto createScheduledRemittance(Long userId, RegularRemittanceCreateDto dto) {
        Account account = accountRepository.findByAccountNo(dto.getAccountNo())
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_ACCOUNT));
        verifyUserOwnership(account.getUserId(), userId);

        RecipientCreateDto recipientCreateDto = new RecipientCreateDto(
                dto.getRecipientName(),
                dto.getRecipientAccountNo(),
                dto.getRecipientBankName(),
                dto.getRecipientBankCode(),
                dto.getRecipientPhoneCc(),
                dto.getRecipientPhoneNo(),
                dto.getRecipientCountry(),
                dto.getReceiveCurrency()
        );
        Recipient recipient = recipientFactory.findOrCreate(recipientCreateDto);

        RegularRemittance newRemittance;
        if ("MONTHLY".equalsIgnoreCase(dto.getRegRemType())) {
            newRemittance = MonthlyRegularRemittance.of(
                    account,
                    recipient,
                    dto.getSendCurrency(),
                    dto.getReceiveCurrency(),
                    dto.getSendAmount(),
                    dto.getScheduledDate(),
                    dto.getStartedAt()
            );
        } else if ("WEEKLY".equalsIgnoreCase(dto.getRegRemType())) {
            newRemittance = WeeklyRegularRemittance.of(
                    account,
                    recipient,
                    dto.getSendCurrency(),
                    dto.getReceiveCurrency(),
                    dto.getSendAmount(),
                    dto.getScheduledDay(),
                    dto.getStartedAt()
            );
        } else {
            throw new CustomBaseException(ErrorBaseCode.BAD_REQUEST);
        }

        if (hasDuplicateRemittance(account, recipient, dto)) {
            throw new CustomBaseException(ErrorBaseCode.DUPLICATE_REMITTANCE);
        }

        RegularRemittance savedRemittance = regularRemittanceRepository.save(newRemittance);
        return RegularRemittanceResponseDto.from(savedRemittance);
    }

    /**
     * 기존 정기 해외송금 설정을 수정합니다.
     *
     * @param regRemId 정기송금 ID
     * @param userId   사용자 ID
     * @param dto      정기송금 수정에 필요한 정보를 담은 DTO
     */
    @Transactional
    public void updateScheduledRemittance(Long regRemId, Long userId, RegularRemittanceUpdateDto dto) {
        RegularRemittance remittance = regularRemittanceRepository.findById(regRemId)
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_REGULAR_REMITTANCE));
        verifyUserOwnership(remittance.getAccount().getUserId(), userId);

        Account account = accountRepository.findByAccountNo(dto.getAccountNo())
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_ACCOUNT));
        verifyUserOwnership(account.getUserId(), userId);
        validateRequestedStatus(remittance.getRegRemStatus(), dto.getRegRemStatus());

        remittance.updateDetails(
                account,
                dto.getSendAmount(),
                dto.getRegRemStatus()
        );

        if (remittance instanceof MonthlyRegularRemittance monthly) {
            monthly.updateSchedule(dto.getScheduledDate());
        } else if (remittance instanceof WeeklyRegularRemittance weekly) {
            weekly.updateSchedule(dto.getScheduledDay());
        }
    }

    /**
     * 기존 정기 해외송금 설정을 삭제합니다.
     *
     * @param regRemId 삭제할 정기송금의 ID
     * @param userId   사용자 ID
     */
    @Transactional
    public void deleteScheduledRemittance(Long regRemId, Long userId) {
        RegularRemittance remittance = regularRemittanceRepository.findById(regRemId)
                .orElseThrow(() -> new CustomBaseException(ErrorBaseCode.NOT_FOUND_REGULAR_REMITTANCE));
        verifyUserOwnership(remittance.getAccount().getUserId(), userId);

        regularRemittanceRepository.delete(remittance);
    }

    /**
     * 사용자가 요청한 정기송금 상태가 허용되는지 확인합니다.
     * DELAYED는 배치가 "다음 평일에 다시 실행"할 대상으로 쓰는 시스템 상태라, 사용자가 새로 지정하면
     * 예약일이 아닌 날 송금이 실행됩니다. 이미 DELAYED인 정기송금을 그대로 보내는 경우(다른 항목만 수정)는 허용합니다.
     *
     * @param currentStatus   현재 상태
     * @param requestedStatus 요청한 상태
     */
    private void validateRequestedStatus(RegRemStatus currentStatus, RegRemStatus requestedStatus) {
        if (requestedStatus == RegRemStatus.DELAYED && currentStatus != RegRemStatus.DELAYED) {
            throw new CustomBaseException(ErrorBaseCode.INVALID_REGULAR_REMITTANCE_STATUS);
        }
    }

    private void verifyUserOwnership(Long ownerId, Long requesterId) {
        if (!Objects.equals(ownerId, requesterId)) {
            throw new CustomBaseException(ErrorBaseCode.FORBIDDEN);
        }
    }

    private boolean hasDuplicateRemittance(Account account, Recipient recipient, RegularRemittanceCreateDto dto) {
        if ("MONTHLY".equalsIgnoreCase(dto.getRegRemType())) {
            Integer scheduledDate = dto.getScheduledDate();
            if (scheduledDate == null) {
                return false;
            }
            return regularRemittanceRepository.existsMonthlyDuplicate(
                    account,
                    recipient,
                    dto.getSendCurrency(),
                    dto.getReceiveCurrency(),
                    dto.getSendAmount(),
                    scheduledDate
            );
        } else if ("WEEKLY".equalsIgnoreCase(dto.getRegRemType())) {
            if (dto.getScheduledDay() == null) {
                return false;
            }
            return regularRemittanceRepository.existsWeeklyDuplicate(
                    account,
                    recipient,
                    dto.getSendCurrency(),
                    dto.getReceiveCurrency(),
                    dto.getSendAmount(),
                    dto.getScheduledDay()
            );
        }
        return false;
    }

}
