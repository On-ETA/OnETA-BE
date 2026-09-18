package com.OnETA.service;

import com.OnETA.entity.User;
import com.OnETA.entity.UserDeviceToken;
import com.OnETA.repository.UserDeviceTokenRepository;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceTokenService {

    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public void registerOrUpdateToken(String email, String token) {
        if (token == null || token.isBlank() || token.length() > 255
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "deviceToken은 공백 없는 255자 이하의 문자열이어야 합니다.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.USER_NOT_FOUND));

        Optional<UserDeviceToken> existingToken = userDeviceTokenRepository.findByUserId(user.getId());

        userDeviceTokenRepository.findByDeviceToken(token)
                .filter(other -> existingToken.isEmpty() || !other.getId().equals(existingToken.get().getId()))
                .ifPresent(other -> {
                    throw new com.OnETA.common.exception.GlobalException(
                            com.OnETA.common.error.ErrorCode.DEVICE_TOKEN_CONFLICT);
                });

        if (existingToken.isPresent()) {
            existingToken.get().updateToken(token);
        } else {
            UserDeviceToken newToken = new UserDeviceToken(user, token);
            userDeviceTokenRepository.save(newToken);
        }
    }
}
