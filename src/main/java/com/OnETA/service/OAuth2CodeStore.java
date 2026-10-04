package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;


import java.time.Duration;
import java.util.UUID;
import java.util.Map;

@Service
public class OAuth2CodeStore {
    private static final String PREFIX = "auth:oauth2:code:";
    private final StringRedisTemplate redis;
    private final Duration ttl = Duration.ofMinutes(5);
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OAuth2CodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // tempId 를 전달 받아 code(K)-tempId(V) 정보 Redis 저장
    // code(Key) 값 반환
    public String generateCode(Map<String, Object> data) {
        String code = UUID.randomUUID().toString();

        try {
            String value = mapper.writeValueAsString(data);
            redis.opsForValue().set(PREFIX + code, value, ttl);
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "일회용 코드 저장에 실패했습니다.");
        }

        return code;
    }

    public Map<String, Object> consumeCode(String code) {
        String key = PREFIX + code;
        String value = redis.opsForValue().get(key);

        if (value == null) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "유효하지 않거나 만료된 코드입니다.");
        }

        redis.delete(key);

        try {
            return mapper.readValue(value, Map.class);
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "일회용 코드 데이터를 읽는 데 실패했습니다.");
        }
    }
}
