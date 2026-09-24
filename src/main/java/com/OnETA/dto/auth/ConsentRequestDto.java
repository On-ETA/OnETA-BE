package com.OnETA.dto.auth;

import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

@Getter
@NoArgsConstructor
public class ConsentRequestDto {

    @NotBlank(message = "임시 가입 ID는 필수입니다.")
    private String tempId;

    // 서비스 이용약관 동의 (필수)
    private boolean serviceTermsAgreement;

    // 개인정보 수집 및 이용 동의 (필수)
    private boolean personalInfoAgreement;
}
