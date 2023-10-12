package com.xndp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xndp.dto.Result;
import com.xndp.dto.UserDTO;
import com.xndp.entity.Follow;
import com.xndp.entity.User;
import com.xndp.mapper.FollowMapper;
import com.xndp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xndp.service.IUserService;
import com.xndp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.判断
        return Result.ok(count > 0);
    }

    //关注与取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2.判断是关注还是取关
        if (isFollow) {
            //2.1关注，新增关系数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //把关注的用户id，存到Redis的set集合中 sadd key value
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //2.2取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                //将其从Redis的set集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result commonFollow(Long followUserId) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;//用户关注集合的key
        String key2 = "follows:" + followUserId;//查询用户的关注集合key
        //2.查询两个集合交集(共同关注)
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            //没有共同好友，无交集
            return Result.ok(Collections.emptyList());
        }
        //3.解析出id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户，并保护隐私，转换为userDTO对象
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
