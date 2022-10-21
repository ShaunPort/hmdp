package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONBeanParser;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private boolean tryLock(String key) {
        Boolean flag =  redisTemplate.opsForValue().setIfAbsent(key, "10", Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL));
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 可以防止缓存穿透的查询商铺
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        // 1. 查redis
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 命中
        if (shopJson != null && !shopJson.isEmpty()) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 命中 空对象
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }
        // 3. 未命中 : 查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", Duration.ofMinutes(RedisConstants.CACHE_NULL_TTL));
            return null;
        }
        // 4. 存在即写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shop), Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL));
        // 5. 返回
        return shop;
    }

    /**
     * 防缓存穿透和击穿
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        // 1. 查redis
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 命中
        if (shopJson != null && !shopJson.isEmpty()) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 命中 空对象
        if (shopJson != null && shopJson.equals("")) {
            return null;
        }
        // 3. 未命中 : 查询数据库    -》 重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 3.1 尝试获取互斥锁
        while (!tryLock(lockKey)) {
            // 3.2 失败就休眠并重试获取互斥锁
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        }
        // 4. 再次检测是否已经缓存重建
        shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (shopJson != null && !shopJson.isEmpty()) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            unLock(lockKey);
            return shop;
        }
        if (shopJson != null && shopJson.equals("")) {
            unLock(lockKey);
            return null;
        }
        // 开始重建
        // 5. 查数据库
        Shop shop = getById(id);
        if (shop == null) {
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", Duration.ofMinutes(RedisConstants.CACHE_NULL_TTL));
            unLock(lockKey);
            return null;
        }
        // 6. 存在即写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shop), Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL));
        // 6.1 施放锁
        unLock(lockKey);
        // 7. 返回
        return shop;
    }

    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(2);

    private Shop queryWithLogicalExpire(Long id) {
        // 1. 查redis
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(shopKey);
        // 2. 未命中
        if (shopJson == null || shopJson.isEmpty()) {
            return null;
        }
        // 3. 命中
        RedisData<Shop> redisData = JSONUtil.toBean(shopJson, new TypeReference<RedisData<Shop>>() {}, false);
        Shop shop = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.1. 未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            log.debug("{}:{}",expireTime, LocalDateTime.now());
            return shop;
        }
        // 3.2. 过期缓存重建
        // 4
        // 4.1 获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 4.2 失败 返回过期的数据
        if (tryLock(lockKey)) {
            // 4.3 成功 在次检查
            redisData = JSONUtil.toBean(shopJson, new TypeReference<RedisData<Shop>>() {}, false);
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                // 再次检查发现没有过期了
                return redisData.getData();
            }
            // 5 开启独立线程 重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    Thread.sleep(200);
                    saveShop2Redis(id, 10L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        // 6. 返回
        return shop;
    }

    @Override
    public Result queryById(Long id) {
        // 查询
        // Shop shop = queryWithPassThrough(id);
        // Shop shop = queryWithMutex(id);
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        // 6. 返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 写入数据库
        updateById(shop);
        // 2. 删除redis缓存
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    public void saveShop2Redis(Long id , Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2。 封装逻辑过期
        RedisData<Shop> redisData = new RedisData<Shop>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
