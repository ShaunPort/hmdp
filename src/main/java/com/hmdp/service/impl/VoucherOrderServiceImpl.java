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
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Wrapper;
import java.time.LocalDateTime;

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

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 未开始
            return Result.fail("秒杀还未开始");
        }
        // 3、判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已结束
            return Result.fail("秒杀已经结束");
        }
        // 4、判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return  Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        DistributedLatch latch = new AtomicDLock("shop:" + userId, stringRedisTemplate);
        if(!latch.tryLock(360)) {
            return Result.fail("一人只能下一单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createCoucherOrder(voucherId);
        }finally {
            latch.unlock();
        }
    }

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


}
