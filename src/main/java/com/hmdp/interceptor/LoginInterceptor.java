package com.hmdp.interceptor;

import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.BaseContext;
import com.hmdp.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器
 */
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 前置拦截，在Controller执行之前
     * 做登录验证校验
     * */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();
        //获取session中的用户
        Object user = session.getAttribute("user");
        //判断用户是否存在
        if (user == null) {
            //不存在，拦截，返回401状态码：未授权
            response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
            return false;
        }
        //存在，保存用户信息到ThreadLocal中
        BaseContext.set((UserDTO)user);
        log.debug("拦截器BaseContext.UserVo = {}",(UserDTO)BaseContext.get());
        //放行
        return true;
    }

    /**
     * 后置拦截，在Controller执行之后，返回数据之前
     * */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception{
        HandlerInterceptor.super.postHandle(request,response,handler,modelAndView);
    }

    /**
     * 在视图渲染之后，返回给用户之前
     * 业务执行完后，销毁用户信息，避免内存泄漏
     * */
    @Override
    public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception ex) throws Exception{
        BaseContext.remove();
    }
}
