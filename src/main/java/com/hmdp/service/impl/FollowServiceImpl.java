package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    FollowMapper followMapper;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 关注用户，取关用户
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        // 是否关注该用户
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        if (count != null && count > 0) {
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result common(Long otherId) {
        Long id = UserHolder.getUser().getId();
        /**
         * 借助Redis 求交集
         */
//        List<Long> follows1 = followMapper.queryAllFollowUserIdByUserId(id);
//        List<Long> follows2 = followMapper.queryAllFollowUserIdByUserId(otherId);
//        if (follows1.isEmpty() || follows2.isEmpty()) {
//            return Result.ok(Collections.EMPTY_LIST);
//        }
//        String followStr1 = follows1.stream().map(String::valueOf).collect(Collectors.joining(" "));
//        String followStr2 = follows2.stream().map(String::valueOf).collect(Collectors.joining(" "));
//        stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOWER_CACHE_KEY + id, followStr1);
//        stringRedisTemplate.opsForSet().add(RedisConstants.FOLLOWER_CACHE_KEY + otherId, followStr2);
//        stringRedisTemplate.expire(RedisConstants.FOLLOWER_CACHE_KEY + id, Duration.ofSeconds(RedisConstants.FOLLOWER_CACHE_TTL));
//        stringRedisTemplate.expire(RedisConstants.FOLLOWER_CACHE_KEY + otherId, Duration.ofSeconds(RedisConstants.FOLLOWER_CACHE_TTL));
//        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(RedisConstants.FOLLOWER_CACHE_KEY + id, RedisConstants.FOLLOWER_CACHE_KEY + otherId);
        List<Long> common = followMapper.queryCommonFollower(id, otherId);
        return Result.ok(common);
    }
}
