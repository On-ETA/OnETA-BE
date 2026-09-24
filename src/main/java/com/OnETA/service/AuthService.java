package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.auth.*;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import com.OnETA.security.JwtProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // 비밀번호 암호화 도구
    private final JwtProvider jwtProvider;         // 토큰 생성 도구
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;

    private final PendingSignupStore pendingSignupStore;

    // 일반 회원가입
    public SignupResponseDto signup(SignupRequestDto request) {

        // 비밀번호와 비밀번호 확인 일치 검사
        if(!request.getPassword().equals(request.getPasswordConfirm())){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        // 이미 가입된 이메일 여부 확인
        if (userRepository.findByEmail(request.getEmail()).filter(user -> user.getRole() == Role.USER).isPresent()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이미 사용 중인 이메일입니다.");
        }

        // 이메일 인증 여부 검사
        EmailVerification verification = emailVerificationRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이메일 인증 내역이 없습니다. 이메일 인증을 먼저 진행해주세요."));

        if (!java.time.LocalDateTime.now().isBefore(verification.getExpirationTime())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이메일 인증 유효 시간이 만료되었습니다. 다시 인증해 주세요.");
        }

        if(!verification.isVerified()){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이메일 인증이 완료되지 않았습니다. 인증번호를 확인해 주세요.");
        }

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 닉네임이 전달되지 않았다면 랜덤 닉네임 부여
        String nickname = request.getNickname();
        if (nickname == null || nickname.trim().isEmpty()) {
            nickname = com.OnETA.util.NicknameGenerator.generate();
        }

        SignupResponseDto response = pendingSignupStore.create(request.getEmail(), encodedPassword, nickname);
        emailVerificationRepository.delete(verification);
        return response;
    }

    // 약관 동의 전에는 회원 DB와 JWT를 생성하지 않는다.
    public TokenResponseDto processConsent(ConsentRequestDto consentDto) {
        if (!consentDto.isServiceTermsAgreement() || !consentDto.isPersonalInfoAgreement()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "필수 약관에 모두 동의해야 서비스 이용이 가능합니다.");
        }
        PendingSignupStore.PendingSignup pending = pendingSignupStore.consume(consentDto.getTempId());
        User user = userRepository.findForSignupByEmail(pending.email()).orElse(null);
        if (user != null && user.getRole() == Role.USER) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이미 정식 가입이 완료된 사용자입니다. 로그인해 주세요.");
        }
        if (user == null) {
            user = User.builder().email(pending.email()).password(pending.passwordHash())
                    .nickname(pending.nickname()).role(Role.USER).build();
        } else {
            // 기존 GUEST는 재인증한 정보로 가입 완료. 기존 ID/연관 데이터는 보존한다.
            user.updatePassword(pending.passwordHash());
            user.updateNickname(pending.nickname());
            user.upgradeToUser();
        }
        try {
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입 정보를 저장할 수 없습니다. 이미 가입한 이메일인지 확인해 주세요.");
        }
        return issueUserTokens(user);
    }

    public SignupResponseDto startSocialSignup(String email) {
        return pendingSignupStore.create(email, null, com.OnETA.util.NicknameGenerator.generate());
    }

    public TokenResponseDto loginSocialUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));
        return issueUserTokens(user);
    }

    private TokenResponseDto issueUserTokens(User user) {
        requireUser(user);
        String accessToken = jwtProvider.createAccessToken(user.getEmail(), Role.USER.getKey());
        String refreshToken = jwtProvider.createRefreshToken(user.getEmail());
        saveOrUpdateRefreshToken(user.getEmail(), refreshToken);
        return new TokenResponseDto(accessToken, refreshToken);
    }

    private void requireUser(User user) {
        if (user.getRole() != Role.USER) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED,
                    "약관 동의가 완료되지 않은 계정입니다. 회원가입을 다시 진행해 주세요.");
        }
    }

    // 일반 로그인
    public TokenResponseDto login(LoginRequestDto request) {
        // 이메일로 유저 찾기
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));

        requireUser(user);

        // 비밀번호 일치 여부 확인 (입력받은 원문과 DB의 암호화된 문자열 비교)
        if (user.getPassword() == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "비밀번호가 일치하지 않습니다.");
        }

        // 액세스, 리프레시 토큰 발급
        String accessToken = jwtProvider.createAccessToken(user.getEmail(), user.getRole().getKey());
        String refreshToken = jwtProvider.createRefreshToken(user.getEmail());

        // 리프레시 토큰 DB 저장 (이미 있다면 업데이트)
        saveOrUpdateRefreshToken(user.getEmail(), refreshToken);

        // 비밀번호가 맞다면 JWT 토큰을 발급하여 돌려줌
        return new TokenResponseDto(accessToken, refreshToken);
    }

    // 리프레시 토큰 저장 내부 로직
    private void saveOrUpdateRefreshToken(String email, String refreshToken) {
        refreshTokenRepository.findByEmail(email)
                .ifPresentOrElse(
                        token -> token.updateToken(refreshToken),
                        () -> refreshTokenRepository.save(new RefreshToken(email, refreshToken))
                );
    }

    // 새로운 Access Token 재발급(Reissue) 로직
    public TokenResponseDto reissueToken(String refreshToken) {
        // 1. 전달받은 리프레시 토큰이 유효한지 검사
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "Refresh Token이 유효하지 않거나 만료되었습니다.");
        }

        // 2. 토큰에서 이메일 추출 및 DB와 비교
        String email = jwtProvider.getEmailFromToken(refreshToken);
        RefreshToken savedToken = refreshTokenRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "로그인 기록이 없습니다."));

        if (!savedToken.getToken().equals(refreshToken)) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "토큰 정보가 일치하지 않습니다.");
        }

        // 3. 유저 권한 확인
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "사용자를 찾을 수 없습니다."));

        requireUser(user);

        // 4. 새로운 토큰들 발급 및 DB 업데이트 (토큰 로테이션)
        String newAccessToken = jwtProvider.createAccessToken(email, user.getRole().getKey());
        String newRefreshToken = jwtProvider.createRefreshToken(email);

        savedToken.updateToken(newRefreshToken);

        return new TokenResponseDto(newAccessToken, newRefreshToken);
    }

    // DB에서 리프레시 토큰을 찾음
    public void logout(String email) {
        refreshTokenRepository.findByEmail(email)
                .ifPresent(refreshToken -> {
                    refreshTokenRepository.delete(refreshToken); // 토큰이 존재 시 DB에서 삭제, 토큰 갱신 끊기
                });
    }

    // 이메일 인증 여부 확인 및 비밀번호 찾기(재설정)
    public void resetPassword(PasswordResetRequestDto request){

        // 새 비밀번호와 비밀번호 확인이 일치하는지 검증
        if (!request.getNewPassword().equals(request.getNewPasswordConfirm())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        // 가입된 유저인지 확인
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));

        // 소셜 연동 회원가입 유저는 password Column 이 null 임
        if (user.getPassword() == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "소셜 연동으로 가입된 계정입니다. 소셜 로그인을 이용해주세요.");
        }

        // 이메일 인증 여부 검사
        EmailVerification verification = emailVerificationRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이메일 인증 내역이 없습니다. 인증번호 발송을 먼저 진행해주세요."));

        if (java.time.LocalDateTime.now().isAfter(verification.getExpirationTime())) {
            emailVerificationRepository.delete(verification); // 만료 데이터 삭제
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "인증 유효 시간이 만료되었습니다. 처음부터 다시 진행해 주세요.");
        }

        if(!verification.isVerified()){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "이메일 인증이 완료되지 않았습니다. 인증번호를 확인해 주세요.");
        }

        // 검증 성공 시 request Dto 에서 newPassword 변수 추출하여 encode 후 User 메소드로 password 업데이트
        String encodedPassword = passwordEncoder.encode(request.getNewPassword());
        user.updatePassword(encodedPassword);

        emailVerificationRepository.delete(verification);
    }



}