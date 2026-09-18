package com.OnETA.entity;

public enum NotificationCategory {
    SCHEDULE, TRANSIT;

    public static NotificationCategory of(NotificationScheduleType type) {
        return type == null || type == NotificationScheduleType.NORMAL ? SCHEDULE : TRANSIT;
    }
}
