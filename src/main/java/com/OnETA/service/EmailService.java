package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.entity.EmailVerification;
import com.OnETA.repository.EmailVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Transactional
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender javaMailSender;
    private final EmailVerificationRepository emailVerificationRepository;

    private static final int MAX_DAILY_SEND_COUNT = 100; // 하루 최대 발송 가능 횟수
    private static final int COOLDOWN_MINUTES = 1;     // 재발송 대기 시간 (1분)
    private static final int MAX_ATTEMPT_COUNT = 5;    // 인증번호 최대 입력 시도 횟수

    // 인증번호 발송 및 DB 저장 로직
    public void sendVerificationCode(String toEmail) {

        LocalDateTime now = LocalDateTime.now();
        String verificationCode = generateRandomCode(); // 6자리 난수 생성
        LocalDateTime expirationTime = LocalDateTime.now().plusMinutes(5); // 만료 시간 5분 설정

        // 기존에 인증 요청을 한 적이 있는지?
        Optional<EmailVerification> existingOpt = emailVerificationRepository.findByEmail(toEmail);

        if (existingOpt.isPresent()) {// DB에 열이 이미 존재한다면 코드와 만료 시간만 업데이트
            EmailVerification existing = existingOpt.get();

            // 쿨다운 검사: 마지막 발송 후 1분이 지나지 않았다면 차단
            if (existing.getLastSentAt() != null && existing.getLastSentAt().plusMinutes(COOLDOWN_MINUTES).isAfter(now)){
                throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증번호 발송은 "+ COOLDOWN_MINUTES + "분마다 가능합니다. 잠시 후 다시 시도해 주세요.");
            }

            // 일일 한도 검사: 날짜가 같은데 최대 횟수를 넘겼다면 차단
            if (existing.getLastSentAt() != null && existing.getLastSentAt().toLocalDate().isEqual(LocalDate.now())){
                if (existing.getSendCount() >= MAX_DAILY_SEND_COUNT){
                    throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "일일 인증번호 발송 횟수(" + MAX_DAILY_SEND_COUNT + "회)를 초과했습니다. 내일 다시 시도해 주세요.");
                }
            }

            existing.updateVerification(verificationCode, expirationTime);
        }
        else { // 처음 요청이라면 DB에 새로운 열 생성
            EmailVerification newVerification = EmailVerification.builder()
                    .email(toEmail)
                    .verificationCode(verificationCode)
                    .expirationTime(expirationTime)
                    .build();

            emailVerificationRepository.save(newVerification);
        }

        // 실제 이메일 전송
        sendEmail(toEmail, verificationCode);
    }

    // 인증번호 검증 로직
    @Transactional(noRollbackFor = GlobalException.class)
    public void verifyCode(String email, String inputCode) {
        EmailVerification verification = emailVerificationRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증 요청 내역이 없습니다."));

        // 시도 횟수 초과 검사
        if (verification.getAttemptCount() >= MAX_ATTEMPT_COUNT){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증 시도 횟수(" + MAX_ATTEMPT_COUNT + "회)를 초과했습니다. 인증번호를 다시 발급받아 주세요.");
        }

        // 시간 만료
        if (LocalDateTime.now().isAfter(verification.getExpirationTime())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증번호가 만료되었습니다. 다시 요청해 주세요.");
        }

        // 인증번호 불일치
        if (!verification.getVerificationCode().equals(inputCode)) {
            verification.increaseAttemptCount(); // 시도 횟수 1 증가
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증번호가 일치하지 않습니다.");
        }

        // 검증 성공 시 DB 상태 변경
        verification.verifySuccess();
    }

    // 코드(난수) 생성 유틸리티 메서드
    private String generateRandomCode() {
        int code = 100000 + ThreadLocalRandom.current().nextInt(900000); // 100000 ~ 999999 숫자

        return String.valueOf(code);
    }

    // 스프링 메일 발송 유틸리티 메서드
    private void sendEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[온에타] 이메일 인증번호 안내");
        message.setText("안녕하세요.\n온에타 앱 이용을 위한 인증번호입니다.\n\n"
                + "인증번호: " + code + "\n\n"
                + "해당 인증번호는 5분간 유효합니다.");

        javaMailSender.send(message);
    }
}