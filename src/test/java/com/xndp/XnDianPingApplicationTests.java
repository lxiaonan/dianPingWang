package com.xndp;

import com.xndp.entity.Shop;
import com.xndp.service.impl.ShopServiceImpl;
import com.xndp.utils.RedisConstants;
import com.xndp.utils.RedisIdWorker;
import com.xndp.utils.cacheClient;
import io.lettuce.core.api.sync.RedisGeoCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.xndp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.xndp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class XnDianPingApplicationTests {
    @Resource
    private cacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //开启500个线程池
    private static final ExecutorService ex = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            ex.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("times = " + (end - start));
    }


    @Test
    void saveTest() {
        Shop shop = shopService.getById(1l);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10l, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> shops = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        //key 是typeId value是店铺  采用stream流便捷分组
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型的店铺集合
            List<Shop> value = entry.getValue();
//            List<RedisGeoCommands> locations = new ArrayList<>(value.size());
            //3.3写入Redis
            for (Shop shop : value) {
                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
//                locations.add(new GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY()));
            }
//            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
