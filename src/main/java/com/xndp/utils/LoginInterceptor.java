package com.xndp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    //前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //因为在第一个拦截器，已经将UserDto对象存线程里面了（ThreadLocal）
        //所以只需要判断是否拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null) {
            //不存在拦截,并返回401状态码
            response.setStatus(401);
            return false;
        }
        //有用户则，放行
        return true;
    }

}
