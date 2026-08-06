package com.HomeRun.service;

import com.HomeRun.common.error.ErrorCode;
import com.HomeRun.common.exception.GlobalException;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Converts the API's weekday list to the integer mask persisted in the database. */
@Service
public class RepeatDaysService {
    private static final List<String> DAY_NAMES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    public int toMask(Collection<String> days) {
        if (days == null || days.isEmpty()) return 0; // one-time notification

        int mask = 0;
        for (String day : days) {
            if (day == null) throw invalidDay();
            String normalized = day.trim().toUpperCase(Locale.ROOT);
            int index = DAY_NAMES.indexOf(normalized);
            if (index < 0) throw invalidDay();
            mask |= 1 << index;
        }
        return mask;
    }

    public List<String> toDays(Integer mask) {
        if (mask == null || mask == 0) return List.of();
        if ((mask & ~0b1111111) != 0) throw invalidDay();

        List<String> days = new java.util.ArrayList<>();
        for (int i = 0; i < DAY_NAMES.size(); i++) {
            if ((mask & (1 << i)) != 0) days.add(DAY_NAMES.get(i));
        }
        return days;
    }

    public boolean includes(Integer mask, DayOfWeek dayOfWeek) {
        return mask != null && mask != 0 && (mask & (1 << (dayOfWeek.getValue() - 1))) != 0;
    }

    private GlobalException invalidDay() {
        return new GlobalException(ErrorCode.INVALID_INPUT_VALUE,
                "repeatDays는 MON, TUE, WED, THU, FRI, SAT, SUN 중 하나여야 합니다.");
    }
}
