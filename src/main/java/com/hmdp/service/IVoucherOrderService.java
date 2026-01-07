package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  优惠券订单Service
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀下单优惠券
     * @param voucherId 优惠券id
     * @return 下单id
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建秒杀订单
     * @param voucherId 优惠券id
     * @return 订单id
     */
    Result createSecKillVoucherOrder(Long voucherId);
}
