package com.hmdp.utils.latch;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-25 13:22
 */
public class SimpleDLock implements DistributedLatch{
    private static final String key_prefix = "Latch:";
    private String name;

    private StringRedisTemplate template;

    /**
     * 构造一个简单的分布式锁
     * @param name 锁名字
     * @param template Redis操作器
     */
    public SimpleDLock(String name, StringRedisTemplate template) {
        this.name = name;
        this.template = template;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean flag = template.opsForValue().setIfAbsent(key_prefix + name, "", Duration.ofSeconds(timeoutSec));
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unlock() {
        template.delete(key_prefix + name);
    }
}
