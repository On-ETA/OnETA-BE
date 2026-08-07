package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String verificationCode;

    @Column(nullable = false)
    private LocalDateTime expirationTime; // 만료 시간 (발송 시점 + 5분)

    @Column(name = "verified", nullable = false)
    private boolean verified; // 인증 성공 여부

    @Column(nullable = false)
    private int sendCount; // 일일 발송 횟수

    @Column
    private LocalDateTime lastSentAt; // 마지막 발송 시간

    @Column(nullable = false)
    private int attemptCount; // 인증 시도 횟수

    @Builder
    public EmailVerification(String email, String verificationCode, LocalDateTime expirationTime) {
        this.email = email;
        this.verificationCode = verificationCode;
        this.expirationTime = expirationTime;
        this.verified = false;
        this.sendCount = 1; // 최초 생성 시 1회 발송으로 카운트
        this.lastSentAt = LocalDateTime.now();
        this.attemptCount = 0;
    }

    // 사용자가 인증번호를 재요청할 경우 기존 레코드를 업데이트
    public void updateVerification(String newCode, LocalDateTime newExpirationTime) {
        LocalDateTime now = LocalDateTime.now();

        if(this.lastSentAt != null && this.lastSentAt.toLocalDate().isBefore(LocalDate.now())){
            this.sendCount = 0;
        }

        this.verificationCode = newCode;
        this.expirationTime = newExpirationTime;
        this.verified = false; // 재발송 시 인증 상태 초기화
        this.attemptCount = 0; // 새 인증번호 발급 시 시도 횟수 초기화
        this.sendCount += 1; // 발송 횟수 1 증가
        this.lastSentAt = now; // 마지막 발송 시간 갱신
    }

    public void increaseAttemptCount() { // 인증 실패 시 시도 횟수 증가
        this.attemptCount += 1;
    }

    // 인증 성공 시 상태를 변경
    public void verifySuccess() {
        this.verified = true;
    }
}