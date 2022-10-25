package com.hmdp.utils.latch;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-25 15:03
 */
public class AtomicDLock implements DistributedLatch{
    private static final String KEY_PREFIX = "Latch:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    private String name;
    private String lockId;
    private StringRedisTemplate template;


    /**
     * 构造一个简单的分布式锁
     * @param name 锁名字
     * @param template Redis操作器
     */
    public AtomicDLock(String name, StringRedisTemplate template) {
        this.name = name;
        this.template = template;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        lockId = UUID.randomUUID().toString();
        Boolean flag = template.opsForValue().setIfAbsent(KEY_PREFIX + name, lockId, Duration.ofSeconds(timeoutSec));
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unlock() {
        template.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), lockId);
    }
}
