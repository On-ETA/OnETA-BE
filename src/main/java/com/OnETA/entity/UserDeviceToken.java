package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_device_tokens")
public class UserDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_token", nullable = false, unique = true)
    private String deviceToken;

    public UserDeviceToken(User user, String deviceToken) {
        this.user = user;
        this.deviceToken = deviceToken;
    }

    public void updateToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }
}
