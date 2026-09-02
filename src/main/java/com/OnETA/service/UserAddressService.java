package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.UserAddressDto;
import com.OnETA.entity.User;
import com.OnETA.entity.UserAddress;
import com.OnETA.repository.UserAddressRepository;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAddressService {

    private static final int MAX_ADDRESS_COUNT = 5;

    private final UserAddressRepository userAddressRepository;
    private final UserRepository userRepository;

    @Transactional
    public UserAddressDto.Response create(String email, UserAddressDto.CreateRequest request) {
        validateCoordinates(request == null ? null : request.getX(), request == null ? null : request.getY());
        User user = getUser(email);
        List<UserAddress> addresses = userAddressRepository.findAllByUserIdOrderByIdAsc(user.getId());

        if (userAddressRepository.existsByUserIdAndXAndY(user.getId(), request.getX(), request.getY())) {
            throw new GlobalException(ErrorCode.ADDRESS_ALREADY_EXISTS);
        }

        // 주소는 사용자당 최대 5개까지만 허용한다. 일반적인 중복 클릭은 프론트에서도 차단한다.
        if (addresses.size() >= MAX_ADDRESS_COUNT) {
            throw new GlobalException(ErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }

        String name = normalizeName(request.getName());
        if (name == null) name = nextDefaultName(addresses);
        String addressText = normalizeAddress(request.getAddress());
        if (addressText == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "실제 주소는 필수입니다.");
        }

        // 첫 번째 주소는 별도 선택 과정 없이 기본 목적지로 사용한다.
        UserAddress address = new UserAddress(
                user, name, addressText, request.getX(), request.getY(), addresses.isEmpty());
        return UserAddressDto.Response.from(userAddressRepository.save(address));
    }

    public List<UserAddressDto.Response> getAll(String email) {
        User user = getUser(email);
        return userAddressRepository.findAllByUserIdOrderByIdAsc(user.getId()).stream()
                .map(UserAddressDto.Response::from)
                .toList();
    }

    // 외부 조회 API와 별개로 경로 검색에서 기본 목적지를 결정할 때 사용한다.
    public UserAddress getCurrentEntity(String email) {
        User user = getUser(email);
        return userAddressRepository.findByUserIdAndCurrentTrue(user.getId())
                .orElseThrow(() -> new GlobalException(ErrorCode.CURRENT_ADDRESS_NOT_SET));
    }

    @Transactional
    public UserAddressDto.Response update(String email, Long addressId, UserAddressDto.UpdateRequest request) {
        validateUpdateRequest(request);
        User user = getUser(email);
        UserAddress address = getOwnedAddress(user.getId(), addressId);

        String name = request.getName() == null ? null : normalizeName(request.getName());
        if (request.getName() != null && name == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "주소 이름은 공백일 수 없습니다.");
        }
        String addressText = request.getAddress() == null ? null : normalizeAddress(request.getAddress());
        if (request.getAddress() != null && addressText == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "실제 주소는 공백일 수 없습니다.");
        }
        if (request.getX() != null) {
            validateCoordinates(request.getX(), request.getY());
            // 자기 자신을 제외한 다른 저장 주소와 좌표가 겹치는 경우만 수정 거부한다.
            if (userAddressRepository.existsByUserIdAndXAndYAndIdNot(
                    user.getId(), request.getX(), request.getY(), addressId)) {
                throw new GlobalException(ErrorCode.ADDRESS_ALREADY_EXISTS);
            }
        }

        address.update(name, addressText, request.getX(), request.getY());
        return UserAddressDto.Response.from(address);
    }

    @Transactional
    public void delete(String email, Long addressId) {
        User user = getUser(email);
        UserAddress address = getOwnedAddress(user.getId(), addressId);
        boolean wasCurrent = address.isCurrent();
        userAddressRepository.delete(address);

        // 현재 주소를 지웠다면 남아 있는 가장 오래된 주소를 기본값으로 이어서 사용한다.
        if (wasCurrent) {
            userAddressRepository.findAllByUserIdOrderByIdAsc(user.getId()).stream()
                    .filter(candidate -> !candidate.getId().equals(addressId))
                    .findFirst()
                    .ifPresent(candidate -> candidate.setCurrent(true));
        }
    }

    @Transactional
    public UserAddressDto.Response setCurrent(String email, Long addressId) {
        User user = getUser(email);
        UserAddress selected = getOwnedAddress(user.getId(), addressId);

        // 한 사용자의 기존 선택을 해제한 뒤 요청한 주소 하나만 현재 주소로 지정한다.
        userAddressRepository.findAllByUserIdOrderByIdAsc(user.getId())
                .forEach(address -> address.setCurrent(address.getId().equals(selected.getId())));
        return UserAddressDto.Response.from(selected);
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.USER_NOT_FOUND));
    }

    private UserAddress getOwnedAddress(Long userId, Long addressId) {
        return userAddressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new GlobalException(ErrorCode.ADDRESS_NOT_FOUND));
    }

    private static void validateUpdateRequest(UserAddressDto.UpdateRequest request) {
        if (request == null || (request.getName() == null && request.getAddress() == null
                && request.getX() == null && request.getY() == null)) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "수정할 값을 입력해주세요.");
        }
        if ((request.getX() == null) != (request.getY() == null)) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "x, y 좌표는 함께 입력해주세요.");
        }
    }

    private static void validateCoordinates(Double x, Double y) {
        if (x == null || y == null || x < 124 || x > 132 || y < 33 || y > 39) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "대한민국 범위의 x, y 좌표를 입력해주세요.");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String normalized = name.trim();
        if (normalized.length() > 50) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "주소 이름은 50자 이하여야 합니다.");
        }
        return normalized;
    }

    private static String normalizeAddress(String address) {
        if (address == null || address.trim().isEmpty()) return null;
        String normalized = address.trim();
        if (normalized.length() > 255) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "실제 주소는 255자 이하여야 합니다.");
        }
        return normalized;
    }

    private static String nextDefaultName(List<UserAddress> addresses) {
        Set<String> names = new HashSet<>();
        addresses.forEach(address -> names.add(address.getName()));
        for (int number = 1; ; number++) {
            String candidate = "주소 " + number;
            if (!names.contains(candidate)) return candidate;
        }
    }
}
