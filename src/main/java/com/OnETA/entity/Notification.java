package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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

    @ElementCollection
    @CollectionTable(name = "notification_reminder_offsets",
            joinColumns = @JoinColumn(name = "notification_id"))
    @Column(name = "reminder_offset_minutes", nullable = false)
    @OrderColumn(name = "offset_order")
    private List<Integer> reminderOffsetMinutes = new ArrayList<>();

    @Column(name = "repeat_days", nullable = false)
    private Integer repeatDays;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_sent_date")
    private LocalDate lastSentDate;

    public Notification(User user, String name, Integer reminderOffsetMinutes, Integer repeatDays) {
        this.user = user;
        this.name = name;
        // Kept for existing Java callers/tests. API-created notifications use the list constructor
        // and are restricted to the supported candidate values in NotificationService.
        if (reminderOffsetMinutes == null || reminderOffsetMinutes < 0) {
            throw new IllegalArgumentException("미리 알림 시간은 0 이상이어야 합니다.");
        }
        this.reminderOffsetMinutes = new ArrayList<>(List.of(reminderOffsetMinutes));
        this.repeatDays = normalizeRepeatDays(repeatDays);
        this.isActive = true;
    }

    public Notification(User user, String name, List<Integer> reminderOffsetMinutes, Integer repeatDays) {
        this.user = user;
        this.name = name;
        this.reminderOffsetMinutes = normalizeReminderOffsets(reminderOffsetMinutes);
        this.repeatDays = normalizeRepeatDays(repeatDays);
        this.isActive = true;
    }

    public void updateCommonInfo(String name, List<Integer> reminderOffsetMinutes) {
        if (name != null) this.name = name;
        if (reminderOffsetMinutes != null) this.reminderOffsetMinutes = normalizeReminderOffsets(reminderOffsetMinutes);
    }

    public Integer getReminderOffsetMinutes() {
        return reminderOffsetMinutes.get(0);
    }

    public List<Integer> getReminderOffsetMinutesList() {
        return List.copyOf(reminderOffsetMinutes);
    }

    public void updateRepeatDays(Integer repeatDays) {
        this.repeatDays = normalizeRepeatDays(repeatDays);
    }

    public void toggleActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public void updateLastSentDate(LocalDate date) {
        this.lastSentDate = date;
    }

    public void completeOneTimeNotification() {
        this.isActive = false;
    }

    private int normalizeRepeatDays(Integer repeatDays) {
        if (repeatDays == null) return 0;
        if (repeatDays < 0 || repeatDays > 0b1111111) {
            throw new IllegalArgumentException("repeatDays는 0부터 127 사이여야 합니다.");
        }
        return repeatDays;
    }

    private List<Integer> normalizeReminderOffsets(List<Integer> offsets) {
        if (offsets == null || offsets.isEmpty() || offsets.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("미리 알림 시간은 하나 이상 선택해야 합니다.");
        }
        Set<Integer> normalized = new TreeSet<>(offsets);
        if (!Set.of(1, 3, 5, 10, 15, 30, 60).containsAll(normalized)) {
            throw new IllegalArgumentException("미리 알림 시간은 1, 3, 5, 10, 15, 30, 60분만 선택할 수 있습니다.");
        }
        return new ArrayList<>(normalized);
    }
}
