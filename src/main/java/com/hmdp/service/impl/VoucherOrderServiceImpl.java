package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.BaseContext;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  优惠券订单Service实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始");
        }

        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //已经结束
            return Result.fail("秒杀已经结束");
        }

        //判断优惠券是否充足
        if (voucher.getStock() < 1){
            // 优惠券库存不足
            return Result.fail("优惠券库存不足");
        }

        Long userId = ((UserDTO) BaseContext.get()).getId();
        // 创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        // 尝试获取锁
        boolean isLock = lock.tryLock(1200);
        // 判断获取锁是否成功
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createSecKillVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createSecKillVoucherOrder(Long voucherId) {
        Long userId = ((UserDTO) BaseContext.get()).getId();
        // 判断用户是否已经下过单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("该秒杀券已经购买过一次了！");
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("库存不足");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 生成订单号
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 获取用户ID
        voucherOrder.setUserId(userId);
        // 获取优惠券ID
        voucherOrder.setVoucherId(voucherId);
        // 保存订单
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
