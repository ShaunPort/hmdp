package com.hmdp.config;

import com.hmdp.Interceptor.LoginInterceptor;
import com.hmdp.Interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @Author Shawn
 * @Email 947253705@qq.com
 * @Date 2022-10-19 11:29
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {


    @Autowired
    LoginInterceptor loginInterceptor;

    @Autowired
    RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/blog/hot",
                "/blog/*",
                "/shop/**",
                "/shop-type/**",
                "/voucher/**"
        ).order(1);
        registry.addInterceptor(refreshTokenInterceptor).order(0);
        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
