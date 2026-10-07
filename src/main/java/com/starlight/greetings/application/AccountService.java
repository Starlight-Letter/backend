package com.starlight.greetings.application;
import com.starlight.greetings.web.SignupResult;
public interface AccountService {
 String recover(String nickname, String recoveryCode, String newPassword);
 String rotateRecoveryCode(String userId, String password);
 SignupResult changePassword(String userId, String currentPassword, String newPassword);
 void withdraw(String userId, String password);
}
