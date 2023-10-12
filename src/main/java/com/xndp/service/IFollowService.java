package com.xndp.service;

import com.xndp.dto.Result;
import com.xndp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result isFollow(Long followUserId);

    Result follow(Long followUserId, Boolean isFollow);

    Result commonFollow(Long followUserId);
}
