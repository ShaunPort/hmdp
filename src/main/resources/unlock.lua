---
--- Created by yxy.
--- DateTime: 2022/10/25 15:09
---
-- 比较锁id是否相同
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del', KEYS[1])
end
return 0