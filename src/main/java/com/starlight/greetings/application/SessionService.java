package com.starlight.greetings.application;
import com.starlight.greetings.web.SessionView;
public interface SessionService { SessionView current(String token); void logout(String token, String csrfToken); }
