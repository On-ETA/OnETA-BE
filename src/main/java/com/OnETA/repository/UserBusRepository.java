package com.OnETA.repository;

import com.OnETA.entity.BusDirection;
import com.OnETA.entity.UserBus;
import com.OnETA.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserBusRepository extends JpaRepository<UserBus, Long> {
    List<UserBus> findAllByUser(User user);

    Optional<UserBus> findByUserAndRouteIdAndDirection(User user, String routeId, BusDirection direction);
}