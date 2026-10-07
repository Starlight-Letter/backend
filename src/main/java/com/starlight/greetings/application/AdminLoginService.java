package com.starlight.greetings.application;
import com.starlight.greetings.web.SignupResult;
public interface AdminLoginService { SignupResult login(String nickname,String password,String totp); }
