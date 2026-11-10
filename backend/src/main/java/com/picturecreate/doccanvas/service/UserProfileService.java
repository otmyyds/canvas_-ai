package com.picturecreate.doccanvas.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.api.dto.UserDtos.UserProfileResponse;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

/**
 * 用户资料查询：只读 users 表现有字段，并附带画布数量。
 */
@Service
public class UserProfileService {

    private final UserAccountRepository userAccountRepository;
    private final BoardRepository boardRepository;

    public UserProfileService(UserAccountRepository userAccountRepository, BoardRepository boardRepository) {
        this.userAccountRepository = userAccountRepository;
        this.boardRepository = boardRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse profile(CurrentUser user) {
        UserAccount account = userAccountRepository.findById(user.id())
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        return new UserProfileResponse(account.getId(), account.getEmail(), account.getDisplayName(),
                account.getCreatedAt(), account.getUpdatedAt(), boardRepository.countByOwnerId(account.getId()));
    }
}
