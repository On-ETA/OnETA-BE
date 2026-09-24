package com.OnETA.service;

import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.auth.*;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import com.OnETA.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    private final EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
    private final PendingSignupStore pending = mock(PendingSignupStore.class);
    private final AuthService service = new AuthService(users, encoder, jwt, refresh, verifications, pending);
    private final String email = "test@example.com";

    private SignupRequestDto request() {
        SignupRequestDto dto = new SignupRequestDto();
        dto.setEmail(email);
        dto.setPassword("password123");
        dto.setPasswordConfirm("password123");
        dto.setNickname("tester");
        return dto;
    }

    private EmailVerification verified(LocalDateTime expires) {
        EmailVerification verification = new EmailVerification(email, "123456", expires);
        verification.verifySuccess();
        when(verifications.findByEmail(email)).thenReturn(Optional.of(verification));
        return verification;
    }

    private ConsentRequestDto consent(boolean agreed) {
        ConsentRequestDto dto = new ConsentRequestDto();
        ReflectionTestUtils.setField(dto, "tempId", "temp-id");
        ReflectionTestUtils.setField(dto, "serviceTermsAgreement", agreed);
        ReflectionTestUtils.setField(dto, "personalInfoAgreement", agreed);
        return dto;
    }

    private void stubPending() {
        when(pending.consume("temp-id")).thenReturn(new PendingSignupStore.PendingSignup(
                email, "hashed", "new-name", Role.GUEST, System.currentTimeMillis() + 900000));
    }

    @Test
    void signupStoresOnlyTemporaryHashedDataAndDoesNotIssueTokens() {
        EmailVerification verification = verified(LocalDateTime.now().plusMinutes(5));
        when(encoder.encode("password123")).thenReturn("hashed");
        when(pending.create(email, "hashed", "tester")).thenReturn(new SignupResponseDto("temp-id", 900));
        assertThat(service.signup(request()).tempId()).isEqualTo("temp-id");
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(jwt, refresh);
        verify(verifications).delete(verification);
    }

    @Test
    void abandonedLegacyGuestCanRestartAfterEmailVerification() {
        when(users.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).role(Role.GUEST).build()));
        verified(LocalDateTime.now().plusMinutes(5));
        service.signup(request());
        verify(pending).create(eq(email), any(), eq("tester"));
        verifyNoInteractions(jwt, refresh);
    }

    @Test
    void registeredUserAndExpiredOrUnverifiedEmailCannotStartSignup() {
        when(users.findByEmail(email)).thenReturn(Optional.of(User.builder().role(Role.USER).build()));
        assertThatThrownBy(() -> service.signup(request())).isInstanceOf(GlobalException.class);
        when(users.findByEmail(email)).thenReturn(Optional.empty());
        verified(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.signup(request())).isInstanceOf(GlobalException.class);
        when(verifications.findByEmail(email)).thenReturn(Optional.of(
                new EmailVerification(email, "123456", LocalDateTime.now().plusMinutes(5))));
        assertThatThrownBy(() -> service.signup(request())).isInstanceOf(GlobalException.class);
        verifyNoInteractions(pending, jwt, refresh);
    }

    @Test
    void consentCreatesUserThenIssuesTokens() {
        stubPending();
        when(jwt.createAccessToken(email, Role.USER.getKey())).thenReturn("access");
        when(jwt.createRefreshToken(email)).thenReturn("refresh");
        assertThat(service.processConsent(consent(true)).getAccessToken()).isEqualTo("access");
        var order = inOrder(users, jwt, refresh);
        order.verify(users).findForSignupByEmail(email);
        order.verify(users).saveAndFlush(argThat(user -> user.getRole() == Role.USER
                && "hashed".equals(user.getPassword()) && email.equals(user.getEmail())));
        order.verify(jwt).createAccessToken(email, Role.USER.getKey());
        verify(refresh).save(any(RefreshToken.class));
    }

    @Test
    void legacyGuestIsCompletedInPlaceUsingReverifiedDetails() {
        stubPending();
        User guest = User.builder().email(email).password("old").nickname("old").role(Role.GUEST).build();
        when(users.findForSignupByEmail(email)).thenReturn(Optional.of(guest));
        service.processConsent(consent(true));
        assertThat(guest.getRole()).isEqualTo(Role.USER);
        assertThat(guest.getPassword()).isEqualTo("hashed");
        assertThat(guest.getNickname()).isEqualTo("new-name");
        verify(users).saveAndFlush(guest);
    }

    @Test
    void missingAgreementDoesNotConsumeTemporarySignup() {
        assertThatThrownBy(() -> service.processConsent(consent(false))).isInstanceOf(GlobalException.class);
        verifyNoInteractions(pending, users, jwt, refresh);
    }

    @Test
    void missingTempIdOrAlreadyCompletedEmailCannotIssueTokens() {
        when(pending.consume("temp-id")).thenThrow(new GlobalException(
                com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "expired"));
        assertThatThrownBy(() -> service.processConsent(consent(true))).isInstanceOf(GlobalException.class);
        reset(pending);
        stubPending();
        when(users.findForSignupByEmail(email)).thenReturn(Optional.of(User.builder().role(Role.USER).build()));
        assertThatThrownBy(() -> service.processConsent(consent(true))).isInstanceOf(GlobalException.class);
        verifyNoInteractions(jwt, refresh);
    }

    @Test
    void guestCannotLoginOrReissueTokens() {
        User guest = User.builder().email(email).role(Role.GUEST).build();
        when(users.findByEmail(email)).thenReturn(Optional.of(guest));
        LoginRequestDto login = new LoginRequestDto();
        ReflectionTestUtils.setField(login, "email", email);
        ReflectionTestUtils.setField(login, "password", "password123");
        assertThatThrownBy(() -> service.login(login)).isInstanceOf(GlobalException.class);
        when(jwt.validateToken("old-refresh")).thenReturn(true);
        when(jwt.getEmailFromToken("old-refresh")).thenReturn(email);
        when(refresh.findByEmail(email)).thenReturn(Optional.of(new RefreshToken(email, "old-refresh")));
        assertThatThrownBy(() -> service.reissueToken("old-refresh")).isInstanceOf(GlobalException.class);
        verify(jwt, never()).createAccessToken(any(), any());
        verify(jwt, never()).createRefreshToken(any());
    }

    @Test
    void socialSignupDoesNotPersistMemberOrIssueTokens() {
        service.startSocialSignup(email);
        verify(pending).create(eq(email), isNull(), anyString());
        verifyNoInteractions(users, jwt, refresh);
    }
}
