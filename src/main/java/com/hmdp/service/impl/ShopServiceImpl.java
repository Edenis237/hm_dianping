package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id, Shop.class,this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //return queryWithMutex(id);

        //利用逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)){
            // 3.缓存未命中，直接返回空
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return shop;
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
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            // 判断是否逻辑过期
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                // 命中且未过期，直接返回新数据
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.3 返回过期的商铺信息
        return shop;
    }

    /**
     * 根据id查询商铺信息（用互斥锁解决缓存击穿、同时解决缓存穿透）
     * @param id 商铺id
     * @return 查询结果
     */
    private Result queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询店铺数据
        Shop shopFromCache = getShopFromCache(key);
        // 判断缓存是否命中
        if (shopFromCache != null){
            // 缓存命中，直接返回店铺数据
            return Result.ok(shopFromCache);
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 尝试获取锁
            if(!tryLock(lockKey)){
                // 休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁成功，再次查询缓存是否命中
            shopFromCache = getShopFromCache(key);
            if (shopFromCache != null){
                return Result.ok(shopFromCache);
            }
            // 缓存未命中，查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            // 判断数据库中是否存在数据
            if (shop == null){
                //数据库不存在，写入空值
                stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 数据不存在，返回错误
                return Result.fail("店铺不存在");
            }
            // 数据存在，写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        // 返回店铺数据
        return Result.ok(shop);
    }

    /**
     * 从缓存中查询店铺数据
     * @param cacheKey 商铺缓存key
     * @return 商铺详情数据
     */
    private Shop getShopFromCache(String cacheKey){
        // 从redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            // 缓存命中，直接返回店铺数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //防止缓存穿透：判断命中是否是空值,isNotBlank会判断null和空字符串是空，所以要排除为null的情况，留下为空字符串的情况
        if ("".equals(shopJson)) {
            // 当前数据是空字符串（说明该数据是之前缓存的空对象），缓存命中空对象，返回null
            return null;
        }
        // 缓存未命中，返回null
        return null;
    }

    /**
     * 缓存穿透处理
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            // 缓存命中，直接返回店铺数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中是否是空值,isNotBlank会判断null和空字符串是空，所以要排除为null的情况，留下为空字符串的情况
        if ("".equals(shopJson)) {
            return null;
        }
        // 缓存未命中，查询数据库
        Shop shop = getById(id);
        // 判断数据库中是否存在数据
        if (shop == null){
            //数据库不存在，写入空值
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 数据不存在，返回错误
            return null;
        }
        // 数据存在，写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 返回店铺数据
        return shop;
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

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1. 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
    * 更新商铺信息（写操作，先更新数据库，再删除缓存）
    * @param shop 商铺数据
    * @return
    */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        // 先更新数据库
        updateById(shop);
        // 再删缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
