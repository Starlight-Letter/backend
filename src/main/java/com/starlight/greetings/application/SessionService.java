package com.starlight.greetings.application;
import com.starlight.greetings.web.SessionView;
public interface SessionService { SessionView current(String token); SessionView member(String token, String csrfToken); void logout(String token, String csrfToken); }
