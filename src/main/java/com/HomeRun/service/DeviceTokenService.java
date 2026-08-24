package com.HomeRun.service;

import com.HomeRun.entity.User;
import com.HomeRun.entity.UserDeviceToken;
import com.HomeRun.repository.UserDeviceTokenRepository;
import com.HomeRun.repository.UserRepository;
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
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.USER_NOT_FOUND));

        Optional<UserDeviceToken> existingToken = userDeviceTokenRepository.findByUserId(user.getId());

        if (existingToken.isPresent()) {
            userDeviceTokenRepository.findByDeviceToken(token)
                    .filter(other -> !other.getId().equals(existingToken.get().getId()))
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("이미 다른 사용자에게 등록된 디바이스 토큰입니다.");
                    });
            existingToken.get().updateToken(token);
        } else {
            // Also ensure no other user has this token (if it was reassigned to a new device)
            userDeviceTokenRepository.findByDeviceToken(token).ifPresent(userDeviceTokenRepository::delete);
            
            UserDeviceToken newToken = new UserDeviceToken(user, token);
            userDeviceTokenRepository.save(newToken);
        }
    }
}
