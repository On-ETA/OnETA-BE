package com.HomeRun.dto;

import com.HomeRun.entity.UserAddress;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

public class UserAddressDto {

    @Getter
    @Setter
    public static class CreateRequest {
        private String name;
        private String address;
        private Double x;
        private Double y;
    }

    @Getter
    @Setter
    public static class UpdateRequest {
        private String name;
        private String address;
        private Double x;
        private Double y;
    }

    @Getter
    @Builder
    public static class Response {
        private Long addressId;
        private String name;
        private String address;
        private Double x;
        private Double y;
        private boolean isCurrent;

        public static Response from(UserAddress address) {
            return Response.builder()
                    .addressId(address.getId())
                    .name(address.getName())
                    .address(address.getAddress())
                    .x(address.getX())
                    .y(address.getY())
                    .isCurrent(address.isCurrent())
                    .build();
        }
    }
}
