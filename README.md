# 黑马点评后端

## 1 项目初始化

### 1.1 在Mysql中建立hmdp数据库

```mysql
create database hmdp;
```

### 1.2 导入数据库文件

src/main/resources/db/hmdp.sql 文件

### 1.3 修改配置文件

![image-20221019165545267](https://cuihua.top/oss/tortoise_2022-10-19_170726.png)



## 2 开发日志

成功导入黑马点评项目

完成用户手机登录功能

完成商铺缓存功能



## 2.1 手机登录&共享Seesion

### 2.1.1 发送短信流程图

![tortoise_2022-10-24_155457](https://cuihua.top/oss/tortoise_2022-10-24_155457.png)

### 2.1.2 验证登录流程图

![tortoise_2022-10-24_155656](https://cuihua.top/oss/tortoise_2022-10-24_155656.png)

### 2.1.3 实现说明

具体实现看下述文件

Controller  src/main/java/com/hmdp/controller/UserController.java

Service src/main/java/com/hmdp/service/impl/UserServiceImpl.java

使用Intercepter进行拦截

此拦截器进行对相关需要登录权限的api进行拦截判断是否具有权限

```java
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 是否放行（ThreadHold是否有UserDTO）
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 5. 放行
        return true;
    }

}
```

此拦截器进行对Seesion进行刷新，如果已经登录就将登录信息保存至ThreadLocal中。

```java
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (token == null || token.equals("")) {
            return true;
        }
        // 2. 获取token中的用户
        // Object user = session.getAttribute("user");
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(tokenKey);
        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 4. 存在保存用户信息到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        // 刷新Token有效期
        redisTemplate.expire(tokenKey, Duration.ofSeconds(RedisConstants.LOGIN_USER_TTL));
        // 5. 放行
        return true;
    }
}
```

配置拦截器。因为登录拦截器只拦截了需要登录的请求，如果刷新拦截器放在后面的话就不能刷新所有的请求。如果用户登录状态，而只去访问了不需要登录状态的请求，就不会刷新登录状态。所以要在登录拦截器之前放入刷新拦截器。

```java
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {


    @Autowired
    LoginInterceptor loginInterceptor;

    @Autowired
    RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/blog/hot",
                "/shop/**",
                "/shop-type/**",
                "/voucher/**"
        ).order(1);
        registry.addInterceptor(refreshTokenInterceptor).order(0);
        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
```





## 2.2 Redis缓存

### 2.2.1 缓存模型

![tortoise_2022-10-24_161412](https://cuihua.top/oss/tortoise_2022-10-24_161412.png)

### 2.2.2 流程图

![tortoise_2022-10-24_161425](https://cuihua.top/oss/tortoise_2022-10-24_161425.png)

### 2.2.3 实现说明

这是最初实现，但是存在缓存缓存穿透问题

```java
// 1. 查redis
String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
// 2. 命中
if (shopJson != null && !shopJson.isEmpty()) {
  Shop shop = JSONUtil.toBean(shopJson, Shop.class);
  return Result.ok(shop);
}
// 3. 未命中 : 查询数据库
Shop shop = getById(id);
if (shop == null) {
  return Result.fail("商户不存在");
}
// 4. 存在即写入redis
redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonPrettyStr(shop));
// 5. 返回
return Result.ok(shop);
```

于是就使用了src/main/java/com/hmdp/utils/CacheClient.java中的防止缓存穿透的方法，具体实现看代码。

如果是热点问题，为了防止缓存击穿和缓存穿透，就用了上述类中的基于逻辑过期的方法，具体看实现代码。



## 2.3 优惠券秒杀

### 2.3.1 全局ID生成器

当用户抢购时，就会生成订单并保存到tb_voucher_order这张表中，而订单表如果使用数据库自增ID就存在一些问题。在多表的情况下ID会重复，受单表数据量的限制；id的规律性太明显，会被读取私密信息，比如大概的销量。

为了增加ID的安全性，我们可以不直接使用Redis自增的数值，而是拼接一些其它信息：

![tortoise_2022-10-24_161934](https://cuihua.top/oss/tortoise_2022-10-24_161934.png)

ID的组成部分：

-   符号位：1bit，永远为0
-   时间戳：31bit，以秒为单位，可以使用69年
-   序列号：32bit，秒内的计数器，支持每秒产生2^32个不同ID

具体实现看src/main/java/com/hmdp/utils/RedisIdWorker.java文件

**全局唯一ID生成策略**：

-   UUID
-   Redis自增
-   snowflake算法
-   数据库自增 -> 利用id表

**Redis自增ID策略**：

-   每天一个key，方便统计订单量
-   ID构造是 时间戳 + 计数器

### 2.3.2 秒杀流程图

![tortoise_2022-10-24_162205](https://cuihua.top/oss/tortoise_2022-10-24_162205.png)

具体代码看src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java文件

### 2.3.3 处理超卖问题

```java
// 5、扣减库存
boolean success = seckillVoucherService.update()
  .setSql("stock = stock - 1")
  .eq("voucher_id", voucherId).gt("stock", 0)
  .update();
log.debug("{}",success);
if (!success) {
  return Result.fail("库存不足");
}
```

在持久化的过程中，判断是否还有库存，因为在多线程的情况下，可能同时进出减库存的情况。

### 2.3.4 处理一人一单问题

在减库存之前进行判断，这个人是否已经完成订单。

**流程图**

![tortoise_2022-10-24_163326](https://cuihua.top/oss/tortoise_2022-10-24_163326.png)

但是这样还是存在一个用户同时进行判断的情况，会出现问题。所以就不得不用悲观锁，为了降低颗粒度，使用用户ID作为锁对象。

```java
Long userId = UserHolder.getUser().getId();
synchronized (userId.toString().intern()) {
  // 持久化操作......
}
```

因为会先释放锁再提交事务，此时可能就会出现线程安全问题，事务还未提交，但同一用户又在一次进来了。所以就需要封装事务方法，然后在原来的方法中，将整个事务进行加锁。

但是这样是有事务失败的情况，即你加了@Transactional，但是没有事务的效果。

#### 解决方法事务失效问题

```java
synchronized (userId.toString().intern()) {
    // 获取代理对象（事务）
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    // createCoucherOrder就是那个事务方法
    return proxy.createCoucherOrder(voucherId);
}
```

此时就需要把这个方法变成一个接口函数，这样才能被代理

```java
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result createCoucherOrder(Long voucherId);
}
```

而且需要使用一个依赖

```xml
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

并且添加第一个注解，是代理类被暴露出来

```java
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }
}
```





具体代码看src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
