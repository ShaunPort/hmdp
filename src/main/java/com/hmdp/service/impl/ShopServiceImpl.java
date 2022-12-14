package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONBeanParser;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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

    @Autowired
    private CacheClient cacheClient;

    private boolean tryLock(String key) {
        Boolean flag =  redisTemplate.opsForValue().setIfAbsent(key, "10", Duration.ofSeconds(RedisConstants.LOCK_SHOP_TTL));
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        redisTemplate.delete(key);
    }

    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(2);

    @Override
    public Result queryById(Long id) {
        // 查询
//         Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id,
//                 Shop.class,
//                 id2 -> getById(id2),
//                 Duration.ofSeconds(RedisConstants.CACHE_NULL_TTL),
//                 Duration.ofMinutes(RedisConstants.CACHE_SHOP_TTL));
        // Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,id2 -> getById(id2), Duration.ofSeconds(15));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // 1. 是否需要根据坐标查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        }
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = (current * SystemConstants.DEFAULT_PAGE_SIZE);
        // 3. 查询redis、按距离排序、分页，结果：shopId、distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(RedisConstants.SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        // 4. 解析id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        list.stream().skip(from).forEach(r -> {
            String shopId = r.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = r.getDistance();
            distanceMap.put(shopId, distance);
        });
        String idsStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 5. 根据id查询shop
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idsStr + ")").list();
        shops.forEach(shop -> {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        });
        // 6. 返回结果
        return Result.ok(shops);
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
}
