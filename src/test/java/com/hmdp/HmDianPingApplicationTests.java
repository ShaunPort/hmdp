package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService taskPool = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            taskPool.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end -begin));
    }

    @Test
    void testSaveShop(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1, shop, Duration.ofSeconds(15));
    }

    /**
     * 按type分，导入店铺经纬度
     */
    @Test
    void loadShopData(){
        List<Shop> shops = shopService.list();
        // shop -> shop.getTypeId()) 或 Shop::getTypeId
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        map.entrySet().forEach(it -> {
            // 获取类型id
            Long typeId = it.getKey();
            // 获取同类型的店铺的集合
            List<Shop> value = it.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(
//                        RedisConstants.SHOP_GEO_KEY + shop.getTypeId(),
//                        new Point(shop.getX(), shop.getY()),
//                        shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_KEY + typeId, locations);
        });
    }

    @Test
    void testHyperLogLog() {
        // 1488160
        String key = "hmdp:uv";
        Random random = new Random(System.currentTimeMillis());
        String[] values = new String[1000];
        for (int i = 0, j = 0; i < 2000000; ++i, ++j) {
            values[j] = "user_" + i;
            if (j == 999) {
                j = 0;
                stringRedisTemplate.opsForHyperLogLog().add(key, values);
                System.out.println("submit" + i);
            }
        }
    }
}
