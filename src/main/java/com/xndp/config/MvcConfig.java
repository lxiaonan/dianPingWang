package com.xndp.config;

import com.xndp.utils.LoginInterceptor;
import com.xndp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource//是Resource是java自带的注解  Autowired是spring的注解，两者相似
    private StringRedisTemplate stringRedisTemplate;

    //拦截器,InterceptorRegistry注册器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //order(数值)  里面的数值越大，越后执行，小的先执行
        //默认都是0，按照编写顺序执行

        //登录拦截器：拦截部分请求
        registry.addInterceptor(new LoginInterceptor())
                //排除不需要拦截的路径
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**"
                ).order(1);
        //token刷新拦截器，拦截所有请求,默认拦所有请求
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
