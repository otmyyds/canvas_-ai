package com.picturecreate.doccanvas.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.UserDtos.UserProfileResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.UserProfileService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户接口：当前登录用户的资料展示。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserProfileService userProfileService;
    private final CurrentUserService currentUserService;

    public UserController(UserProfileService userProfileService, CurrentUserService currentUserService) {
        this.userProfileService = userProfileService;
        this.currentUserService = currentUserService;
    }

    /** 当前登录用户资料（id、邮箱、昵称、注册/更新时间、画布数量） */
    @GetMapping("/me")
    public UserProfileResponse me(HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return userProfileService.profile(user);
    }
}
