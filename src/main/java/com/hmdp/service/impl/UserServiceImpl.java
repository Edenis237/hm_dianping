package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送手机验证码
     * @param phone 手机号
     * @return
     */
    @Override

    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式是否有效
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 格式不符合，返回错误信息
            return Result.fail("手机格式错误");
        }
        // 格式有效，生产验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到session
        session.setAttribute(phone,code);
        // 模拟发送短信验证码
        log.debug("向{}发送短信验证码成功，验证码：{}", phone, code);
        // 返回ok
        return Result.ok();
    }

    /**
     * 登录，注册
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式错误!");
        }
        // 校验验证码
        Object cacheCode = session.getAttribute(phone);
        String code = loginForm.getCode();

        // 如果没有发送验证码，或者session中验证码过期，验证码不一致
        if (cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误！");
        }
        // 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 判断用户是否存在
        if (user == null){
            // 不存在创建新用户保存并返回
            user = createUserWithPhone(phone);
        }
        //保存用户到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));;
        return Result.ok();
    }

    /**
     * 根据手机号创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
