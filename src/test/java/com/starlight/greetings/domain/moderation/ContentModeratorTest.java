package com.starlight.greetings.domain.moderation;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ContentModeratorTest { private final ContentModerator moderator = new ContentModerator(); @Test void classifiesBaseModerationCases(){assertEquals("profanity",moderator.category("씨발"));assertEquals("personal_info",moderator.category("010-1234-5678"));assertEquals("advertising",moderator.category("https://example.com"));assertNull(moderator.category("오늘도 잘 버텼어요."));}}
