package com.OnETA.security;

import com.OnETA.dto.auth.SignupResponseDto;
import com.OnETA.dto.auth.TokenResponseDto;
import com.OnETA.entity.Role;
import com.OnETA.entity.User;
import com.OnETA.repository.UserRepository;
import com.OnETA.service.AuthService;
import com.OnETA.service.CustomOAuth2UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignupSecurityTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void onlyUserAccessTokenAuthenticatesProtectedRequests() throws Exception {
        JwtProvider jwt = new JwtProvider();
        ReflectionTestUtils.setField(jwt, "secretKeyString", "a-long-test-secret-with-at-least-32-bytes-1234567890");
        ReflectionTestUtils.setField(jwt, "accessTokenValidTime", 60000L);
        ReflectionTestUtils.setField(jwt, "refreshTokenValidTime", 60000L);
        jwt.init();
        String guest = jwt.createAccessToken("test@example.com", Role.GUEST.getKey());
        String refresh = jwt.createRefreshToken("test@example.com");
        for (String token : new String[]{guest, refresh}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            new JwtFilter(jwt).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt.createAccessToken("test@example.com", Role.USER.getKey()));
        new JwtFilter(jwt).doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("test@example.com");
    }

    @Test
    void newSocialUserReceivesOnlyTemporaryId() throws Exception {
        AuthService auth = mock(AuthService.class);
        UserRepository users = mock(UserRepository.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("test@example.com");
        when(principal.getAttribute("email_verified")).thenReturn(true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(auth.startSocialSignup("test@example.com")).thenReturn(new SignupResponseDto("temporary", 900));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new OAuth2SuccessHandler(auth, users).onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("/api/token-test?tempId=temporary&expiresInSeconds=900");
        verify(auth, never()).loginSocialUser(anyString());
        verify(users, never()).save(any());
    }

    @Test
    void registeredSocialUserReceivesTokensThroughAuthService() throws Exception {
        AuthService auth = mock(AuthService.class);
        UserRepository users = mock(UserRepository.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("test@example.com");
        when(principal.getAttribute("email_verified")).thenReturn(true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(User.builder().role(Role.USER).build()));
        when(auth.loginSocialUser("test@example.com")).thenReturn(new TokenResponseDto("access", "refresh"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new OAuth2SuccessHandler(auth, users).onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        assertThat(response.getRedirectedUrl()).contains("accessToken=access", "refreshToken=refresh").doesNotContain("tempId");
        verify(auth, never()).startSocialSignup(anyString());
    }

    @Test
    void unverifiedSocialEmailIsRejected() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("email")).thenReturn("test@example.com");
        assertThatThrownBy(() -> CustomOAuth2UserService.requireVerifiedEmail(principal))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }
}
