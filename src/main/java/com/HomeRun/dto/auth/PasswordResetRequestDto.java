package com.HomeRun.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordResetRequestDto {

    @NotBlank(message = "이메일은 필수 입력값입니다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "새 비밀번호는 필수 입력값입니다.")
    @Pattern(regexp = "^(?:(?=.*[a-zA-Z])(?=.*[0-9])|(?=.*[a-zA-Z])(?=.*[^a-zA-Z0-9\\s])|(?=.*[0-9])(?=.*[^a-zA-Z0-9\\s]))[^\\s]{8,16}$",
            message = "비밀번호는 영문 대소문자, 숫자, 특수문자 중 2가지 이상을 조합하여 8~16자로 입력해주세요. (공백 사용 불가)")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인은 필수 입력값입니다.")
    private String newPasswordConfirm;
}