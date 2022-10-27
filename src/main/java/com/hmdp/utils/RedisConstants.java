package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "hmdp:login:code:";
    public static final Long LOGIN_CODE_TTL = 60L;
    public static final String LOGIN_USER_KEY = "hmdp:login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "hmdp:cache:shop:";

    public static final String LOCK_SHOP_KEY = "hmdp:lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "hmdp:seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "hmdp:seckill:order:";
    public static final String BLOG_LIKED_KEY = "hmdp:blog:liked:";
    public static final String FEED_KEY = "hmdp:feed:";
    public static final String SHOP_GEO_KEY = "hmdp:shop:geo:";
    public static final String USER_SIGN_KEY = "hmdp:sign:";

    public static final String CACHE_SHOP_TYPE_KEY = "hmdp:cache:shop-type";
}
