package com.xndp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    //    同一个线程UUID是相同的
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";//true:是取消下划线

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //采用静态代码块，在运行的时候，会提前加载脚本,提高性能
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //设置脚本所在的位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //尝试获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);//set KEY VALUE NX EX TIME
        //如果直接返回success，就需要拆箱，可能返回的是null，就会出现异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
//    @Override
//    public void unLock() {
//
//        //获取当前线程标识
//        String threadId =ID_PREFIX + Thread.currentThread().getId();
//        //判断标识是否一致
//        String ID = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);        //获取锁中标识
//        if(ID.equals(threadId)){//如果标识一致
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
