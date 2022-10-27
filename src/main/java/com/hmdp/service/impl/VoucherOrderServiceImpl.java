package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.latch.AtomicDLock;
import com.hmdp.utils.latch.DistributedLatch;
import com.hmdp.utils.latch.SimpleDLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.sql.Wrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> checkScript;
    static {
        checkScript = new DefaultRedisScript<>();
        checkScript.setScriptText(
            "if (tonumber(redis.call('get', ARGV[1])) <= 0) then return 1 end " +
            "if (redis.call('sismember', ARGV[2], ARGV[3]) == 1) then return 2 end " +
            "redis.call('incrby', ARGV[1], -1) " +
            "redis.call('sadd', ARGV[2], ARGV[3]) " +
            "return 0"
        );
//        checkScript.setLocation(new ClassPathResource("seckill.lua"));
        checkScript.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        threadPoll.submit(new VoucherOrderHandler());
    }

    private BlockingQueue<VoucherOrder> ordersTasks = new LinkedBlockingDeque<>();
    private static ExecutorService threadPoll = Executors.newSingleThreadExecutor();
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取订单中的信息
                    VoucherOrder order = ordersTasks.take();
                    handleVoucherOrder(order);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }

            }
        }

        private void handleVoucherOrder(VoucherOrder order) {
            // 判断是该用户是否已有订单 -> 实现一人一单
            Long userId = order.getUserId();
            Long voucherId = order.getVoucherId();
            RLock latch = redissonClient.getLock("lock:shop:" + userId);
            boolean isLock = latch.tryLock();
            if(!isLock) {
                log.error("不允许从复下单");
            }
            try {
                // 获取代理对象（事务）
                proxy.createCoucherOrder(order);
                return;
            }finally {
                latch.unlock();
            }

        }
    }

    IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚步
        Long re = stringRedisTemplate.execute(
                checkScript,
                Collections.EMPTY_LIST,
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_KEY + voucherId,
                userId.toString());
        // 2、判断结果是否为0
        switch (re.intValue()) {
            case 1:
                return Result.fail("优惠券不足");
            case 2:
                return Result.fail("不能重复下单");
        }
        // 3、把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        // 3、把下单信息保存到阻塞队列
        ordersTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return  Result.ok(0);
    }


    // 基于分布式锁的优惠券秒杀
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2、判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 未开始
//            return Result.fail("秒杀还未开始");
//        }
//        // 3、判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已结束
//            return Result.fail("秒杀已经结束");
//        }
//        // 4、判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return  Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
////        DistributedLatch latch = new AtomicDLock("shop:" + userId, stringRedisTemplate);
////        boolean isLock = latch.tryLock(360);
//        RLock latch = redissonClient.getLock("lock:shop:" + userId);
//
//        boolean isLock = latch.tryLock();
//        if(!isLock) {
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createCoucherOrder(voucherId);
//        }finally {
//            latch.unlock();
//        }
//    }

    @Transactional
    public Result createCoucherOrder(Long voucherId) {
        // 判断是该用户是否已有订单 -> 实现一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count >0) {
            return Result.fail("该用户已下单");
        }
        // 5、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        log.debug("{}",success);
        if (!success) {
            return Result.fail("库存不足");
        }
        // 6、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7、返回订单id
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createCoucherOrder(VoucherOrder order) {
        // 判断是该用户是否已有订单 -> 实现一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", order.getVoucherId()).count();
        if(count >0) {
            log.error("不允许从复下单");
            return;
        }
        // 5、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId()).gt("stock", 0)
                .update();
        log.debug("{}",success);
        if (!success) {
            log.error("库存不足");
            return;
        }
        save(order);
    }


}
