package com.xndp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

//全局唯一id生成器
@Component
public class RedisIdWorker {
    /**
     * 开始时间轴
     * 2023年一月一号0点0分0秒
     */
    private static final long BEGIN_TIMESTAMP = 1672531200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //当前时间的秒，减去初始时间
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号  为什么要拼接当前日期？
        // 理由1：同一个业务用相同的key，可以会爆
        // 理由2：精确到天，可以统计每一天的订单量
        //2.1获取当前时期精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
        //3.拼接并返回
        return timeStamp << COUNT_BITS | count;
    }

}
