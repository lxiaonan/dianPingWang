package com.xndp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xndp.dto.LoginFormDTO;
import com.xndp.dto.Result;
import com.xndp.dto.UserDTO;
import com.xndp.entity.User;
import com.xndp.mapper.UserMapper;
import com.xndp.service.IUserService;
import com.xndp.utils.RegexUtils;
import com.xndp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Hasxnap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.xndp.utils.RedisConstants.*;
import static com.xndp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j//使用log.debug需要导入注解
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    public StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不合法");
        }
        //2.获取6位随机验证码
        String code = RandomUtil.randomNumbers(6);
//        //3.将验证码保存到session
//        session.setAttribute("code",code);
        //3.将验证码保存到redis以string的形式,还要区分key
        //设置有效期，set key value ex 300
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);//5分钟
        //4.模拟向手机号发送验证码
        log.debug("发送短信成功，验证码是：{}", code);
        return Result.ok();
    }

    /**
     * 登录，并将token登录令牌，返回给前端，前端存到请求头的“authorization”
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.判断手机号是否合法
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不合法
            return Result.fail("手机号不合法");
        }
//        //2.从session中拿到验证码
//        Object code = session.getAttribute("code");
        //2. 从redis中拿到验证码
        String key = LOGIN_CODE_KEY + phone;
        String code = stringRedisTemplate.opsForValue().get(key);//拿到的直接就是String类型
        //3.匹配验证码是否填写正确
        String code1 = loginForm.getCode();
        if (!code1.equals(code)) {
            //如果不正确
            return Result.fail("验证码错误");
        }
        //如果登录成功，将验证码从redis中删除
        stringRedisTemplate.delete(key);

        //4.根据手机号查找用户是否注册
        User user = query().eq("phone", phone).one();

        if (user == null) {
            //5.如果未注册
            user = createUserWithPhone(phone);
            //写入数据库
            save(user);
        }
//        //6.将用户保存到session
//        session.setAttribute("user",user);
        // 6.将用户保存到redis
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 将user转为hash存储
        // 保护用户信息，仅存储部分信息，也减少内存消耗
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //工具类，将对象转为map，userDTO的id字段是long类型，这里需要自定义一下
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new Hasxnap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, filedValue) -> filedValue.toString()));
        //保存数据到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置过期时间，如果用户30分钟无操作，说明token将要过期，可以在拦截器是否工作来判断用户是否活跃
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //7.返回token
        return Result.ok(token);
    }

    /**
     * 实现用户当天签到
     *
     * @return
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3.拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        long today = time.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, today - 1, true);
        return Result.ok("签到成功");
    }

    /**
     * 统计这个月到现在的连续签到天数
     *
     * @return
     */
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime time = LocalDateTime.now();
        String keySuffix = time.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3.拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int today = time.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字
        // get方法：获取一段时间的签到记录，返回值是十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(today)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            //没有任何签到结果
            return Result.ok(0);
        }
        // 6.循环遍历
        int ans = 0;
        while (true) {
            //让这个数字与1做与运算，得到数字的最后一1bit位
            // 判断这个bit位是否为0
            if ((num & 1) == 1) {
                // 如果不为0，说明已签到，计数器+1
                ans++;
                num >>= 1;
            } else {
                // 如果为0，说明未签到，结束
                break;
            }
        }
        return Result.ok(ans + "次");
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        return user;
    }
}
