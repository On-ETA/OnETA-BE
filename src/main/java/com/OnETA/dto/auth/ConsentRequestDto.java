package com.OnETA.dto.auth;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ConsentRequestDto {

    // 서비스 이용약관 동의 (필수)
    private boolean serviceTermsAgreement;

    // 개인정보 수집 및 이용 동의 (필수)
    private boolean personalInfoAgreement;
}
