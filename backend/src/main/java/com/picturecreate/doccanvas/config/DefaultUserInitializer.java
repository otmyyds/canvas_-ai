package com.picturecreate.doccanvas.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

/**
 * 启动时确保演示账号存在：不存在则创建，已存在则跳过。
 * 登录页默认填入该账号，方便本地直接体验。
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

    private static final String DEFAULT_EMAIL = "doc_canvas@doc_canvas.com";
    private static final String DEFAULT_PASSWORD = "doc_canvas";
    private static final String DEFAULT_DISPLAY_NAME = "Doc Canvas";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public DefaultUserInitializer(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userAccountRepository.findByEmail(DEFAULT_EMAIL).isPresent()) {
            log.info("默认账号已存在，跳过初始化：email={}", DEFAULT_EMAIL);
            return;
        }
        UserAccount user = new UserAccount(DEFAULT_EMAIL, DEFAULT_DISPLAY_NAME);
        user.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        userAccountRepository.save(user);
        log.info("默认账号已创建：email={} displayName={}", DEFAULT_EMAIL, DEFAULT_DISPLAY_NAME);
    }
}
