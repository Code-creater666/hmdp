package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;

import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;

import java.time.LocalDateTime;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);     //查询
        //2.判断是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀没开始");
        }
        //3.判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀以结束");
        }
        //4.判断是否有库存
        if (voucher.getStock() < 1){                                                //判断
            //库存不足
            return Result.fail("库存不足");
        }
        //创建订单
        Long userID = UserHolder.getUser().getId();
        //创建自己的锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order" + userID, stringRedisTemplate);

        //使用Redisson锁
        RLock lock = redissonClient.getLock("lock:order" + userID);

        boolean islock = lock.tryLock();
        if (!islock){
            //失败
            return Result.fail("一个人只允许下一单，当前一个用户在多线程请求");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();    //确保锁释放成功
        }
    }

    /**
     * 封装一人一单
     * @param voucherId
     * @return
     */
    @Transactional
    public  Result createVoucherOrder(Long voucherId) {
        //5.一人一单,可能出现线程安全问题
        Long userID = UserHolder.getUser().getId();

        int count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
        if (count > 0) {                                             //2判断
            return Result.fail("用户已经购买过了");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();                                                     //执行
        if (!success) {
            return Result.fail("库存不足");
        }
        //7.创建订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderID = redisIDWorker.nextID("order");
        voucherOrder.setId(orderID);
        //用户id

        voucherOrder.setUserId(userID);
        //优惠券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);                                                //2执行
        //8.返回订单id

        return Result.ok(orderID);

    }
}
