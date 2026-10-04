package com.OnETA.service;

import com.OnETA.entity.Notification;
import com.OnETA.entity.User;
import com.OnETA.repository.EmailVerificationRepository;
import com.OnETA.repository.NotificationRepository;
import com.OnETA.repository.RefreshTokenRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import com.OnETA.repository.UserRepository;
import com.OnETA.util.NicknameGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;

    @Transactional
    public User getUserEnsureNickname(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 닉네임이 없으면 새로 생성 후 저장
        if (user.getNickname() == null || user.getNickname().trim().isEmpty()) {
            user.updateNickname(NicknameGenerator.generate());
            // @Transactional 덕분에 자동 감지(더티 체킹)되어 DB에 업데이트됩니다.
        }

        return user;
    }

    @Transactional
    public void deleteUser(String email) {
        User user = userRepository.findForNotificationByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        Long userId = user.getId();

        // notifications는 users에 대한 ON DELETE CASCADE가 없고,
        // 하위 테이블도 notifications를 참조하므로 FK 역순으로 먼저 정리한다.
        java.util.List<Long> notificationIds = notificationRepository.findAllByUserId(userId).stream()
                .map(Notification::getId)
                .toList();

        if (!notificationIds.isEmpty()) {
            notificationRepository.deleteScheduleSnapshotsByIds(notificationIds);
            notificationRepository.deleteDeliveriesByIds(notificationIds);
            notificationRepository.deleteReminderOffsetsByIds(notificationIds);
            notificationRepository.deleteArrivalRowsByIds(notificationIds);
            notificationRepository.deleteRowsByIds(notificationIds);
        }

        // user_device_tokens는 DB FK에 ON DELETE CASCADE가 없으므로 명시적으로 삭제한다.
        userDeviceTokenRepository.deleteAllByUserId(userId);

        // FK는 아니지만 탈퇴 계정과 연결된 인증 데이터도 함께 제거한다.
        refreshTokenRepository.findByEmail(email).ifPresent(refreshTokenRepository::delete);
        emailVerificationRepository.findByEmail(email).ifPresent(emailVerificationRepository::delete);

        // user_addresses, user_buses 및 depot_notifications는 DB ON DELETE CASCADE로 정리된다.
        userRepository.deleteById(userId);
    }
}
