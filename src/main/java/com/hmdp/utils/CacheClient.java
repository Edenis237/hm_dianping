package com.hmdp.utils;

import cn.hutool.core.lang.func.Func;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dpFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 从redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            // 缓存命中，直接返回店铺数据
            return JSONUtil.toBean(shopJson, type);
        }
        //判断命中是否是空值,isNotBlank会判断null和空字符串是空，所以要排除为null的情况，留下为空字符串的情况
        if ("".equals(shopJson)) {
            return null;
        }
        // 缓存未命中，查询数据库
        R r = dpFallback.apply(id);
        // 判断数据库中是否存在数据
        if (r == null){
            //数据库不存在，写入空值
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 数据不存在，返回错误
            return null;
        }
        // 数据存在，写入缓存
        this.set(key,r,time,unit);
        // 返回店铺数据
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type, Function<ID, R> dpFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)){
            // 3.缓存未命中，直接返回空
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return r;
        }
        //5.2 已过期，需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock){
            //6.3 成功，开启独立线程，实现缓存重建
            // 在线程1重建缓存期间，线程2进行过期判断，假设此时key是过期状态，线程1重建完成并释放锁
            // 线程2立刻获取锁，并启动异步线程执行重建，那此时的重建就与线程1的重建重复了
            // 因此需要在线程2获取锁成功后，在这里再次检测redis中缓存是否过期（DoubleCheck）
            // 如果未过期则无需重建缓存，防止数据过期之后，刚释放锁就有线程拿到锁的情况，重复访问数据库进行重建
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 缓存命中先把json反序列化为逻辑过期对象
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            // 将Object对象转成JSONObject再反序列化为目标对象
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            // 判断是否逻辑过期
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                // 命中且未过期，直接返回新数据
                return r;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dpFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.3 返回过期的商铺信息
        return r;
    }

    /**
     * 尝试获取锁
     * @param lockKey
     * @return
     */
    private boolean tryLock(String lockKey){
        Boolean isGetLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isGetLock);
    }

    /**
     * 释放锁
     * @param lockKey
     */
    private void unlock(String lockKey){
        if (BooleanUtil.isTrue(stringRedisTemplate.hasKey(lockKey))){
            stringRedisTemplate.delete(lockKey);
        }
    }

}

