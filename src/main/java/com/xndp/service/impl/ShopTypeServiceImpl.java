package com.xndp.service.impl;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.xndp.dto.Result;
import com.xndp.entity.ShopType;
import com.xndp.mapper.ShopTypeMapper;
import com.xndp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.xndp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    /**
     * 将首页，店铺类型，写入Redis缓存中，因为店铺类型一般是固定不变的
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        //1.从redis中查找list页面信息
        String key = "list";
        //因为储存到Redis中的值是JSON类型的，所以拿到需要进行类型转换
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否有缓存
        if (StrUtil.isNotBlank(shopTypeJson)) {
            //3.如果存在，直接返回
            //想将其转化为JSON数组，然后在转换为list集合
            List<ShopType> list = JSONUtil.parseArray(shopTypeJson).toList(ShopType.class);//将json转为list
            return Result.ok(list);
        }
        //4.如果没有，就去数据中查找，根据sort字段升序排名
        List<ShopType> list = query().orderByAsc("sort").list();
        if (list == null) {
            //5.如果数据库中没有，直接返回错误
            return Result.fail("查询失败！");
        }
        //6.查询成功，将其转换成JSON存到redis缓存中
        String jsonStr = JSONUtil.toJsonStr(list);//list转JSON
        //缓存过期时间是三十分钟
        stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return Result.ok(list);
    }
}
