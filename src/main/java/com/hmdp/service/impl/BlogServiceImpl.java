package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 1、查blg
        Blog blog = getById(id) ;
        if (blog == null) {
            return Result.fail("不存在");
        }
        // 2、查询blg有关的用户
        fillUser(blog);
        // 3、查询博客是否被该用户点赞
        isLicked(blog);
        return Result.ok(blog);
    }

    private void isLicked(Blog blog) {
        Long id = UserHolder.getUser().getId();
        Double score = redisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), id.toString());
        blog.setIsLike(score == null ? false : true);
    }


    private void fillUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            fillUser(blog);
            isLicked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long uid = UserHolder.getUser().getId();
        // 1、判断用户是否已经点赞
        RLock lock = redissonClient.getLock("hmdp:lock:blocklike:" + id);
        lock.lock();
        try {
            Double score = redisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, uid.toString());
            if (score == null) {
                // 2、如果未点赞，可以点赞
                boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
                if (isSuccess) {
                    redisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, uid.toString(), System.currentTimeMillis());
                }
            } else {
                // 3、否则取消点赞
                boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
                if (isSuccess) {
                    redisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, uid.toString());
                }
            }
        } finally {
            lock.unlock();
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> top5 = redisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0l, 4l);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST);
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query().in("id", ids).last("ORDER BY FIELD(id,"+ idStr +")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean flag = save(blog);
        if (!flag) {
            return Result.fail("新增笔记失败");
        }
        // 查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            // 粉丝id
            Long userId = follow.getUserId();
            // 推送
            String key = RedisConstants.FEED_KEY + userId;
            redisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long last, Long offset) {
        // 1. 获取当前用户
        Long uid =UserHolder.getUser().getId();
        // 2. 查询收件箱
        String key = RedisConstants.FEED_KEY + uid;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = redisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, last, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.EMPTY_LIST );
        }
        // 3. 解析数据 blogIds\minTime\offset
        long minTime = 0;
        int os = 1;
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            blogIds.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 4. 根据id查询blg
        String idsStr = blogIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idsStr + ")").list();
        blogs.forEach(blog -> {
            fillUser(blog);
            isLicked(blog);
        });
        // 5. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }


}
