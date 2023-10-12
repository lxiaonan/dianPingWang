package com.xndp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.xndp.utils.RedisConstants.*;

/**
 * 封装redis工具类
 * 使用到了函数式编程
 */
@Slf4j//日志
@Component
public class cacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //普通key
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    //热点key，解决缓存击穿

    /**
     * @param key   键
     * @param value 值
     * @param time  过期时间
     * @param unit  单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //缓存穿透

    /**
     * @param keyPrefix  键的前缀
     * @param id         查找的id
     * @param type       数据的类型
     * @param dbFallback 查找数据库的函数
     * @param time       过期时间
     * @param unit       时间单位
     * @param <R>        数据对应的实体类
     * @param <ID>       id类型
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1.判断redis是否存在
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {//判空：当参数是"",null,"\t\n"都是false，只有里面是真正的字符串，才是true
            //存在直接返回
            R r = JSONUtil.toBean(json, type);//转换成对象
            return r;
        }
        //解决缓存穿透，如果数据库不存在的值，会存一个空值到redis
        //3.判断命中的，是否是空值
        if (json != null) {//如果不是null，说明shopJson==""，空值
            //返回一个错误信息
            return null;
        }
        //能走到这里，说明是真的为null，也就是redis中没有缓存
        //4.不存在，到数据库中查找
        R r = dbFallback.apply(id);//函数式编程
        if (r == null) {
            //5.如果数据库中不存在，将空值，写到redis中
            //设置空值，解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis,并设置超时时间，30分钟，自动删除，再次访问时写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
        //7.返回
        return r;
    }

    //建立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //缓存击穿，逻辑过期时间解决
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //1.判断redis是否存在
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {//判空：当参数是"",null,"\t\n"都是false，只有里面是真正的字符串，才是true
            return null;
        }
        // 4.命中，需更先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);//要经过两次转型，才能拿到Shop
        // 5.判晰是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {//如果逻辑过期时间，在现在之后，说明还没有过期
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重置缓存
                    //1.从数据库中查找
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    //尝试获取互斥锁
    private boolean tryLock(String key) {
        //如果key已经存在，再建立的时候(setNx)，就会返回false，如果不存在就返回true
        //如果没有释放锁，将在10s后，自行释放
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//因为flag可能为空，所以需要采用工具类
    }

    //释放锁
    private void unLock(String key) {
        //删除这个键
        stringRedisTemplate.delete(key);
    }
}
