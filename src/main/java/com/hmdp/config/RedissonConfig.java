package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-25 15:40
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://cuihua.top:6379").setPassword("947253705");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
