package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    //创建一个固定大小的线程池，有500个线程，用于执行并发任务。
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        //创建一个倒计时锁存器，初始值为300，用于等待300个任务完成。
        CountDownLatch latch = new CountDownLatch(300);

        //定义一个可运行的任务，使用Lambda表达式创建。
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                //调用RedisID生成器，生成一个以"order"为前缀的唯一ID。
                long id = redisIdWorker.nextId("order");
                System.out.printf("id =" + id);
            }
            //任务完成时，倒计时减1。
            latch.countDown();
        };
        //记录开始时间（毫秒）。
        long begin = System.currentTimeMillis();
        //循环300次，提交300个任务。
        for (int i = 0; i < 300; i++) {
            //循环300次，提交300个任务。
            //将任务提交到线程池执行。
            es.submit(task);
        }
        //等待所有任务完成（直到倒计时为0）。
        latch.await();
        //记录结束时间（毫秒）
        long end = System.currentTimeMillis();
        System.out.println("time =" + (end - begin));
    }

    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }
}
