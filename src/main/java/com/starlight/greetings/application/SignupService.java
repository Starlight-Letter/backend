package com.starlight.greetings.application;

import com.starlight.greetings.web.SignupResult;

public interface SignupService {
    SignupResult signup(String nickname, String password);
}
