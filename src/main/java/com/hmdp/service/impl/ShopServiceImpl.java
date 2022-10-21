package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private boolean tryLock(String key) {
        Boolean flag =  redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL));
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
            return null;
        }
        // 6. 存在即写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shop), Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL));
        // 6.1 施放锁
        unLock(lockKey);
        // 7. 返回
        return shop;
    }

    @Override
    public Result queryById(Long id) {
        // 查询
        // Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
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
}
