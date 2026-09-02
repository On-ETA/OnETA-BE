package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.MyPageDto;
import com.OnETA.dto.mypage.*;
import com.OnETA.entity.User;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyPageService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final com.OnETA.repository.NoticeRepository noticeRepository;
    private final PasswordEncoder passwordEncoder; // 비밀번호 암호화 도구
    private final JavaMailSender javaMailSender;

    @Value("${app.version:1.0.0}")
    private String appVersion;

    public MyPageDto.Response getMyPageInfo(String email) {
        // UserService를 통해 닉네임 자동 생성이 보장된 User 객체를 가져옵니다.
        User user = userService.getUserEnsureNickname(email);

        return MyPageDto.Response.builder()
                .nickname(user.getNickname())
                .email(user.getEmail())
                .appVersion(appVersion)
                .build();
    }

    public java.util.List<com.OnETA.dto.mypage.NoticeListResponseDto> getAllNotices() {
        return noticeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(com.OnETA.dto.mypage.NoticeListResponseDto::from)
                .collect(java.util.stream.Collectors.toList());
    }

    @org.springframework.transaction.annotation.Transactional
    public com.OnETA.dto.mypage.NoticeDetailResponseDto getNoticeDetail(Long id) {
        com.OnETA.entity.Notice notice = noticeRepository.findById(id)
                .orElseThrow(() -> new com.OnETA.common.exception.GlobalException(com.OnETA.common.error.ErrorCode.NOTICE_NOT_FOUND));
        
        notice.incrementViewCount();
        return com.OnETA.dto.mypage.NoticeDetailResponseDto.from(notice);
    }

    @Transactional
    public void updateNickname(String email, NicknameUpdateRequestDto request){

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));

        user.updateNickname(request.getNewNickname());
    }

    @Transactional
    public void updatePassword(String email, PasswordUpdateRequestDto request){

        if(!request.getNewPassword().equals(request.getNewPasswordConfirm())){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "새 비밀번호가 서로 일치하지 않습니다.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));

        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "소셜 로그인으로 가입한 회원은 비밀번호를 변경할 수 없습니다.");
        }

        // passwordEncoder.matches(평문, 암호화된 문자열) 순서임
        if(!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "현재 비밀번호가 일치하지 않습니다.");
        }

        if(passwordEncoder.matches(request.getNewPassword(), user.getPassword())){
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "새 비밀번호는 기존 비밀번호와 다르게 설정해야 합니다.");
        }

        // 검증 완료 후 암호화 한 String 으로 DB 업데이트
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));
    }


    public void sendInquiry(String email, InquiryRequestDto request){

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "가입되지 않은 이메일입니다."));

        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo("homerunoffice2026@gmail.com");
        message.setSubject("[HomeRun 앱 1:1 문의] " + request.getTitle());

        String mailText = String.format(
                "■ 문의자 이메일: %s\n■ 문의자 닉네임: %s\n\n■ 문의 내용:\n%s",
                user.getEmail(), user.getNickname(), request.getContent()
        );
        message.setText(mailText);

        try {
            javaMailSender.send(message);
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "문의 메일 전송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }


}
