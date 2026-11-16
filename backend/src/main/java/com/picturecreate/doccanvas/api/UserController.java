package com.picturecreate.doccanvas.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.UserDtos.ChangePasswordRequest;
import com.picturecreate.doccanvas.api.dto.UserDtos.UpdateProfileRequest;
import com.picturecreate.doccanvas.api.dto.UserDtos.UserProfileResponse;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.UserProfileService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 用户接口：当前登录用户的资料查询与修改。
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

    /** 当前登录用户资料（邮箱、昵称、注册/更新时间、画布数量） */
    @GetMapping("/me")
    public Result<UserProfileResponse> me(HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return Result.success(userProfileService.profile(user));
    }

    /** 修改昵称和邮箱 */
    @PatchMapping("/me")
    public Result<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return Result.success(userProfileService.updateProfile(user, body));
    }

    /** 修改密码（校验当前密码） */
    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        userProfileService.changePassword(user, body);
    }
}
