package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false)
    private boolean verified; // 인증 성공 여부

    @Builder
    public EmailVerification(String email, String verificationCode, LocalDateTime expirationTime) {
        this.email = email;
        this.verificationCode = verificationCode;
        this.expirationTime = expirationTime;
        this.verified = false;
    }

    // 사용자가 인증번호를 재요청할 경우 기존 레코드를 업데이트
    public void updateVerification(String newCode, LocalDateTime newExpirationTime) {
        this.verificationCode = newCode;
        this.expirationTime = newExpirationTime;
        this.verified = false; // 재발송 시 인증 상태 초기화
    }

    // 인증 성공 시 상태를 변경
    public void verifySuccess() {
        this.verified = true;
    }
}