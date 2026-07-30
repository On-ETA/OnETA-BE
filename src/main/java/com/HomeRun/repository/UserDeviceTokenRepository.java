package com.HomeRun.repository;

import com.HomeRun.entity.UserDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {
    Optional<UserDeviceToken> findByUserId(Long userId);
    Optional<UserDeviceToken> findByDeviceToken(String deviceToken);
}
