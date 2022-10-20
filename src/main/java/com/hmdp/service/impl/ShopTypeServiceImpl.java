package com.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.catalina.LifecycleState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    StringRedisTemplate redisTemplate;

    @Override
    public Result queryShopType() {
        // 1、 查看redis缓存
        List<String> types = redisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1l);
        // 2、 命中直接返回
        if (types.size() != 0) {
            return Result.ok(types);
        }
        // 3、 查看数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4、 缓存
        JSONArray jsonArray = JSONUtil.parseArray(typeList);
        redisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toList(jsonArray, String.class));
        // 5、 返回
        return Result.ok(typeList);
    }
}
