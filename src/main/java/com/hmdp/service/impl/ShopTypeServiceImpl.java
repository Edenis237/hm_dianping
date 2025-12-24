package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public Result queryTypeList() {
//        // 在redis中查询店铺类型
//        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
//        List<ShopType> shopTypeList = null;
//        // 判断缓存是否命中
//        if (StrUtil.isNotBlank(shopTypeJson)){
//            // 缓存命中，直接返回店铺数据
//            shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
//            return Result.ok(shopTypeList);
//        }
//        // 缓存未命中，查询数据库
//        shopTypeList = this.query().orderByAsc("sort").list();
//        // 判断数据库中是否存在该数据
//        if (shopTypeList == null) {
//            return Result.fail("店铺类型不存在");
//        }
//        // 数据库中数据存在，写入redis中
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
//        return Result.ok(shopTypeList);


//        // 从redis中查询店铺类型
//        ListOperations<String, String> ops = stringRedisTemplate.opsForList();
//        List<ShopType> shopTypeList = null;
//        // 0到-1表示查询List中所有元素
//        List<String>  shopTypeJsonList = ops.range(CACHE_SHOP_TYPE_KEY, 0, -1);
//        //判断缓存是否命中
//        if (CollUtil.isNotEmpty(shopTypeJsonList)){
//            //缓存命中，返回数据
//            shopTypeList = shopTypeJsonList.stream().map((shopTypeJson) -> JSONUtil.toBean(shopTypeJson, ShopType.class)).collect(Collectors.toList());
//            return Result.ok(shopTypeList);
//        }
//        //缓存未命中,去数据库里查询
//        shopTypeList = this.query().orderByAsc("sort").list();
//        // 判断数据库中数据是否存在
//        if (shopTypeList == null) {
//            // 不存在，返回失败信息
//            return Result.fail("店铺类型不存在");
//        }
//        // 数据存在，写入redis中
//        ops.rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList()));
//        // 设置key过期时间
//        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
//        //返回数据
//        return Result.ok(shopTypeList);

        // 从redis中查询店铺类型
        ListOperations ops = redisTemplate.opsForList();
        // 由于配置了序列化和反序列化器，存入Java对象，取出时也为java对象
        List<ShopType> shopTypeList = ops.range(CACHE_SHOP_TYPE_KEY, 0, -1);
        // 判断缓存是否命中
        if(CollUtil.isNotEmpty(shopTypeList)) {
            // 缓存命中，直接返回缓存数据
            return Result.ok(shopTypeList);
        }
        // 缓存未命中，查询数据库
        shopTypeList = this.query().orderByAsc("sort").list();
        // 判断数据库中是否存在该数据
        if(shopTypeList == null) {
            // 数据库中不存在该数据，返回失败信息
            return Result.fail("店铺类型不存在");
        }
        // 数据库中的数据存在，写入Redis,并返回查询的数据
        ops.rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypeList);    // 直接将Java对象存入redis，不需要转为Json字符串再存储
        // 设置key的过期时间
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 将数据库查到数据返回
        return Result.ok(shopTypeList);
    }
}
