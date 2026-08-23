package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "user_addresses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(nullable = false)
    private Double x;

    @Column(nullable = false)
    private Double y;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    public UserAddress(User user, String name, String address, Double x, Double y, boolean current) {
        this.user = user;
        this.name = name;
        this.address = address;
        this.x = x;
        this.y = y;
        this.current = current;
    }

    public void update(String name, String address, Double x, Double y) {
        if (name != null) this.name = name;
        if (address != null) this.address = address;
        if (x != null && y != null) {
            this.x = x;
            this.y = y;
        }
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }
}
