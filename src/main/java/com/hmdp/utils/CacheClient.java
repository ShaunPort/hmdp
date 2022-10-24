package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-21 15:48
 */
@Slf4j
@Component
public class CacheClient {
    @Data
    @Accessors(chain = true)
    private static class CacheDate<T> {
        private LocalDateTime expireTime;
        private T data;
    }

    private static final String CACHE_CLIENT_KEY = "CacheClient:";
    private static final String MUTEX_KEY = CACHE_CLIENT_KEY + "Mutex:";
    private static final Duration MUTEX_TTL = Duration.ofSeconds(10);
    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(2);

    @Autowired
    private  StringRedisTemplate stringRedisTemplate;

    public <ID> boolean latchWithDDL(ID id) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(MUTEX_KEY + id, "", MUTEX_TTL);
        return Boolean.TRUE.equals(flag);
    }

    public <ID> void unLatch(ID id) {
        stringRedisTemplate.delete(MUTEX_KEY + id);
    }

    /**
     * redis中添加value 有过期时间 time
     * @param key
     * @param value
     * @param ttl
     * @param <T>
     */
    public  <T> void set(String key, T value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl);
    }

    /**
     * redis中添加value 逻辑过期 time
     * @param key
     * @param value
     * @param <T>
     */
    public <T> void setWithLogicalExpire(String key, T value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(
                        new CacheDate()
                                .setData(value)
                                .setExpireTime(LocalDateTime.now().plusSeconds(ttl.getSeconds()))
                )
        );
    }

    /**
     * 缓存查询（防穿透）   先查缓存，再查数据库
     * @param keyPrefix
     * @param id
     * @param dbQuery 数据库查询函数
     * @param nullTTL 不存在记录缓存过期时间
     * @param existTTL 存在记录缓存过期时间
     * @param <T> 实体类型
     * @param <ID> id的类型
     * @return
     */
    public <T, ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type,Function<ID, T> dbQuery, Duration nullTTL, Duration existTTL) {
        String key = keyPrefix + id;
        // 1. 查redis
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中
        if (jsonStr != null && !jsonStr.isEmpty()) {
            // 2.2 命中 空对象
            if (jsonStr.equals("")) {
                return null;
            }
            return JSONUtil.toBean(jsonStr, type);
        }
        // 3. 未命中 : 查询数据库
        T t = dbQuery.apply(id);
        // 3.1 记录不存在，缓存空对象，防止缓存穿透
        if (t == null) {
            set(key, "", nullTTL);
            return null;
        }
        // 4. 存在即写入redis
        set(key, t, existTTL);
        // 5. 返回
        return t;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,Function<ID, R> dbQuery, Duration time) {
        // 1. 查redis
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        // 2. 未命中
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        // 3. 命中
        CacheDate<R> redisData = null;
        redisData = JSONUtil.toBean(jsonStr, CacheDate.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(), type);
//        CacheDate<R> redisData = JSONUtil.toBean(jsonStr, new TypeReference<CacheDate<R>>() {}, true);
//        R r = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.1. 未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            log.debug("暂未过期,还有{}过期",Duration.between(LocalDateTime.now(), expireTime).toString());
            return r;
        }
        // 3.2. 过期缓存重建
        // 4
        // 4.1 获得互斥锁
        // 4.2 失败 返回过期的数据
        if (latchWithDDL(id)) {
            // 4.3 成功 在次检查
            redisData = JSONUtil.toBean(jsonStr, CacheDate.class);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                // 再次检查发现没有过期了
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            // 5 开启独立线程 重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 1. 查询数据
                    R t = dbQuery.apply(id);
                    // 2. 写入Redis
                    setWithLogicalExpire(key, t, time);
                    log.debug("缓存重建成功");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLatch(id);
                }
            });
        }
        // 6. 返回
        return r;
    }
}
