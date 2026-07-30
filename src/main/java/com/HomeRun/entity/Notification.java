package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "notification_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications")
public abstract class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(name = "reminder_offset_minutes", nullable = false)
    private Integer reminderOffsetMinutes;

    @Column(name = "repeat_days", nullable = false)
    private String repeatDays;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_sent_date")
    private LocalDate lastSentDate;

    public Notification(User user, String name, Integer reminderOffsetMinutes, String repeatDays) {
        this.user = user;
        this.name = name;
        this.reminderOffsetMinutes = reminderOffsetMinutes;
        this.repeatDays = repeatDays;
        this.isActive = true;
    }

    public void updateCommonInfo(String name, Integer reminderOffsetMinutes, String repeatDays) {
        if (name != null) this.name = name;
        if (reminderOffsetMinutes != null) this.reminderOffsetMinutes = reminderOffsetMinutes;
        if (repeatDays != null) this.repeatDays = repeatDays;
    }

    public void toggleActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public void updateLastSentDate(LocalDate date) {
        this.lastSentDate = date;
    }
}
