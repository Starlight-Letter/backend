package com.starlight.greetings.application;

import com.starlight.greetings.web.SignupResult;

public interface LoginService {
    SignupResult login(String nickname, String password);
}
