package com.xndp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.xndp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.xndp.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    //因为这个类是自己写的，没有带注解，所以不能使用自动注入
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取session对象
//        HttpSession session = request.getSession();

        //1.获取请求头中的token,前端存的是authorization
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {//工具类判空
            return true;//伪拦截，不处理
        }
//        //2.拿到session中用户信息
//        User user = (User) session.getAttribute("user");
        //2.基于token获取redis中的用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate
                .opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        //4.将查询到的hash数据转回userDto对象
        UserDTO userDTO = BeanUtil.fillBeanWitxnap(userMap, new UserDTO(), false);
        //5.存在，要保存信息到ThreadLocal，是保存在当前线程中的
        UserHolder.saveUser(userDTO);
        //6.刷新token的过期时间，因为经过了拦截器，肯定说明用户活跃，就要刷新token的保存时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7.放行
        return true;
    }

    //渲染之后，返回给用户之前
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //销毁用户信息，避免内存泄露
        UserHolder.removeUser();
    }
}
