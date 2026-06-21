package com.javaee.docmanager.user.service;

import com.javaee.docmanager.user.dto.LoginDTO;
import com.javaee.docmanager.user.dto.RegisterDTO;
import com.javaee.docmanager.user.entity.User;
import com.javaee.docmanager.user.vo.LoginVO;
import com.javaee.docmanager.user.vo.UserVO;

import java.util.List;

public interface UserService {

    LoginVO login(LoginDTO loginDTO);

    UserVO register(RegisterDTO registerDTO);

    User getUserByUsername(String username);

    UserVO getUserById(Long id);

    String refreshToken(String refreshToken);

    List<UserVO> listActiveUsers();

    List<UserVO> listAllForManage();

    void changePassword(Long userId, String oldPassword, String newPassword);

    void adminResetPassword(Long targetUserId, String newPassword);

    void adminUpdateStatus(Long targetUserId, Integer status);
}
