package com.HomeRun.repository;

import com.HomeRun.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    long countByUserId(Long userId);

    List<UserAddress> findAllByUserIdOrderByIdAsc(Long userId);

    Optional<UserAddress> findByIdAndUserId(Long id, Long userId);

    Optional<UserAddress> findByUserIdAndCurrentTrue(Long userId);

    boolean existsByUserIdAndXAndY(Long userId, Double x, Double y);

    boolean existsByUserIdAndXAndYAndIdNot(Long userId, Double x, Double y, Long id);
}
