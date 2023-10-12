package com.xndp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;//过期时间
    private Object data;//万能的存储，比如存储shop
}
