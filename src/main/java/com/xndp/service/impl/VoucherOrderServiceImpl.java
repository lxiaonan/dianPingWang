package com.xndp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.xndp.dto.Result;
import com.xndp.entity.VoucherOrder;
import com.xndp.mapper.VoucherOrderMapper;
import com.xndp.service.ISeckillVoucherService;
import com.xndp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xndp.utils.RedisIdWorker;
import com.xndp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //采用静态代码块，在运行的时候，会提前加载脚本,提高性能
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //设置脚本所在的位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建一个线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct //当前类初始化完毕，就执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明没有信息，进行下一次循环
                        continue;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWitxnap(values, new VoucherOrder(), true);
                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认  xack stream.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                } catch (Exception e) {
                    handlePendingList();
                    log.debug("订单创建异常！", e);
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 xreadgroup group g1 c1 count 1 streams stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明pending-list没有信息，直接结束循环
                        break;
                    }
                    //3.解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWitxnap(values, new VoucherOrder(), true);
                    //4.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认  xack stream.order g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                } catch (Exception e) {
                    log.debug("pending-list订单创建异常！", e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }

    //阻塞队列,需要赋值初始空间
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//
//            try {
//                //1.获取队列中的订单信息
//                //take()：如果队列中没有元素，则会等待
//                VoucherOrder voucherOrder = orderTasks.take();
//                //2.创建订单
//                handleVoucherOrder(voucherOrder);
//            } catch (Exception e) {
//                log.debug("订单创建异常！",e);
//            }
//
//        }
//    }
    //因为此业务是异步处理的，不再需要返回给前端
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);//使用Redisson的方法
        //尝试获取锁
        boolean success = lock.tryLock();//无参情况是，默认不等待，三十秒超时时间
        if (!success) {
            //如果获取锁失败
            log.debug("一人限购一单！");
            return;
        }
        try {
            //获取代理对象(事务)
            //通过所在方法，和调用方法，都是在实现IVoucherOrderService接口里的方法，这样事务才不会实效
            currentProxy.createVoucherOrder(voucherOrder);//方法执行完，事务提交，然后再释放锁
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    //全局变量
    private IVoucherOrderService currentProxy;

    //根据Redis的Stream数据类型，实现异步下单
    @Override
    public Result secKillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderID = redisIdWorker.nextId("order");
        // 1.执行Lua脚本，尝试判断用户是否有购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderID));
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //3获取代理对象(事务)
        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderID);
    }

    //    //根据Redis完成秒杀资格的判断
//    @Override
//    public Result secKillVoucher(Long voucherId) {
//        //获取用户id
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行Lua脚本，尝试判断用户是否有购买资格
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        int r = result.intValue();
//        // 2.判断结果是否为0
//        if(r != 0){
//            // 2.1.不为0，代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
//        }
//
//        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
//        // 2.3.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.4订单id
//        long orderID = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderID);
//        // 2.5用户id
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);//优惠代金券id
//
//        //2.6放入阻塞队列
//        //其实到这里业务就结束了，结果直接返回给用户了
//        //剩下的对数据库的操作，就交给子线程异步处理了
//        orderTasks.add(voucherOrder);
//
//        //3获取代理对象(事务)
//        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 4.返回订单id
//        return Result.ok(orderID);
//    }
    //异步执行，所以不用返回值
    @Transactional//因为有两张表的操作，加上事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        //5.1根据优惠券id和用户id查订单表，得到数量count
        Long userId = voucherOrder.getUserId();//因为是另开一个子线程来执行当前业务的，所以不能再采用ThreadLocal来获取
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2count是否大于0
        if (count > 0) {
            //说明用户之前购买了
            log.debug("一人限购一张,请勿重复购买！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where id = ? and stock > 0
                .update();
        if (!success) {
            //如果扣减库存失败
            log.debug("优惠券已经售完！");
            return;
        }
        save(voucherOrder);//将订单保存到数据库

    }
    //根据数据库完成秒杀资格的判断
//    @Override
//    public Result secKillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {//开始时间，在当前时间之后
//            //还未开始
//            return Result.fail("秒杀还未开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {//结束时间，在当前时间之前
//            //已经结束
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("优惠券已经售完！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);//使用Redisson的方法
//        //尝试获取锁
//        boolean success = lock.tryLock();//无参情况是，默认不等待，三十秒超时时间
//        if (!success) {
//            //如果获取锁失败
//            return Result.fail("一人限购一单！");
//        }
//        try {
//            //获取代理对象(事务)
//            //通过所在方法，和调用方法，都是在实现IVoucherOrderService接口里的方法，这样事务才不会实效
//            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//            return currentProxy.createVoucherOrder(voucherId);//方法执行完，事务提交，然后再释放锁
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }
//    //采用悲观锁
//    @Transactional//因为有两张表的操作，加上事务
//    public Result createVoucherOrder(Long voucherId) {
//        //5.一人一单
//        //5.1根据优惠券id和用户id查订单表，得到数量count
//        Long userId = UserHolder.getUser().getId();
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        //5.2count是否大于0
//        if (count > 0 ) {
//            //说明用户之前购买了
//            return Result.fail("一人限购一张,请勿重复购买！");
//        }
//        // 6.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")//set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock",0)//where id = ? and stock > 0
//                .update();
//        if (!success){
//            //如果扣减库存失败
//            return Result.fail("优惠券已经售完！");
//        }
//        // 7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //7.1获取全局唯一id
//        long orderId = redisIdWorker.nextId("order");
//        //7.2订单id
//        voucherOrder.setId(orderId);
//        //7.3用户id
//
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);//优惠代金券id
//        save(voucherOrder);//将订单保存到数据库
//        // 7.返回订单id
//        return Result.ok(orderId);
//    }
}
