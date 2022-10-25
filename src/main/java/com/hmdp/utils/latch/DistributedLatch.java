package com.hmdp.utils.latch;

/**
 * 分布式锁接口
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-25 13:19
 */
public interface DistributedLatch {

    public boolean tryLock(long timeoutSec);

    public void unlock();
}
