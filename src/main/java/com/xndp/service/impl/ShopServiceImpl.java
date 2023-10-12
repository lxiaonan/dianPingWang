package com.xndp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xndp.dto.Result;
import com.xndp.entity.Shop;
import com.xndp.mapper.ShopMapper;
import com.xndp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xndp.utils.RedisData;
import com.xndp.utils.SystemConstants;
import com.xndp.utils.cacheClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Hasxnap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.xndp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private cacheClient cacheClient;

    /**
     * 根据id查询店铺
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWitxnutex(id);
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);

    }

    //建立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //缓存击穿，逻辑过期时间解决
    public Shop queryWithLogicalExpire(Long id) {
        //1.判断redis是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {//判空：当参数是"",null,"\t\n"都是true，只有里面是真正的字符串，才是false
            return null;
        }
        // 4.命中，需更先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);//要经过两次转型，才能拿到Shop
        // 5.判晰是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {//如果逻辑过期时间，在现在之后，说明还没有过期
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重置缓存
                    this.saveShopRedis(id, 30l);//更新逻辑过期时间
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }
        // 6.4.返回过期的商铺信息
        return shop;
    }

    //缓存击穿，互斥锁实现
    //写入Redis中，都要采用JSON格式
    public Shop queryWitxnutex(Long id) {
        //1.判断redis是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//判空：当参数是"",null,"\t\n"都是false，只有里面是真正的字符串，才是true
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //3.判断命中的，是否是空值
        if (shopJson != null) {//如果不是null，说明shopJson==""，空值
            //返回一个错误信息
            return null;
        }
        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则体眠并重试
                Thread.sleep(LOCK_SHOP_TTL);//休眠10毫秒
                queryWitxnutex(id);//再次判断redis缓存是否存在，因为之前可能有线程，获取了锁，并且写入了redis
            }
            //4.4.成功，根据id到数据库中查找
            shop = getById(id);
            //模拟重建的延时，因为本地数据库拿值比较快
            Thread.sleep(200);
            if (shop == null) {
                //5.如果数据库中不存在，将空值，写到redis中
                //设置空值，解决缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis,并设置超时时间，30分钟，自动删除，再次访问时写入缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException();//抛出异常，不处理
        } finally {//最终一定要释放锁的
            //7.释放锁
            unLock(lockKey);
        }
        //8.返回
        return shop;
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long id) {
        //1.判断redis是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//判空：当参数是"",null,"\t\n"都是false，只有里面是真正的字符串，才是true
            //存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //3.判断命中的，是否是空值
        if (shopJson != null) {//如果不是null，说明shopJson==""，空值
            //返回一个错误信息
            return null;
        }
        //4.不存在，到数据库中查找
        Shop shop = getById(id);
        if (shop == null) {
            //5.如果数据库中不存在，将空值，写到redis中
            //设置空值，解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis,并设置超时时间，30分钟，自动删除，再次访问时写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    /**
     * @param id            店铺id
     * @param expireSeconds 逻辑过期时间
     */
    //设置逻辑过期时间，解决缓存击穿
    public void saveShopRedis(Long id, Long expireSeconds) {
        //1.根据id查询店铺
        Shop shop = getById(id);
        //封装成逻辑过期对象
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //当前时间，加上一个逻辑过期时间，加上多少秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //将其转为json存进redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    //尝试获取互斥锁
    private boolean tryLock(String key) {
        //如果key已经存在，再建立的时候(setNx)，就会返回false，如果不存在就返回true
        //如果没有释放锁，将在10s后，自行释放
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//因为flag可能为空，所以需要采用工具类
    }

    //释放锁
    private void unLock(String key) {
        //删除这个键
        stringRedisTemplate.delete(key);
    }

    //先更新店铺，再删除缓存，之后再次访问时，自动写入缓存
    @Override
    @Transactional//开启事务
    public Result update(Shop shop) {
        String key = CACHE_SHOP_KEY + shop.getId();//缓存中的键
        //1.判断id是否为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        //2.数据库中更新店铺
        updateById(shop);
        //3.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    /**
     * 根据商品类型和当前坐标查询
     *
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否需要根据坐标x，y查询
        if (x == null || y == null) {
            // 不需要标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数   前端传入的current值是随着向下滑不断自增1的
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序，分页 结果：shopId ， distance距离
        String key = SHOP_GEO_KEY + typeId;
        //geoSearch key x y distance withDistance 分页
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)//分页只能指定结束
        );
        //4.解析id
        if (results == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            //就不用分页跳过了
            return Result.ok();
        }
        //截取from ~ end 部分  skip(from)跳过前面的部分
        // 逻辑分页
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> map = new Hasxnap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //获取距离
            Distance distance = result.getDistance();
            map.put(shopId, distance);
        });
        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
