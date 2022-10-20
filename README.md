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



## 2.2 Redis缓存

图片-》缓存模型

图片-》流程图

### 2.2.1 商户查询缓存

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

