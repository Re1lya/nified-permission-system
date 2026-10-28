package com.cdwy.permission;

import com.cdwy.permission.controller.ApiEnvelopeAdvice;
import com.cdwy.permission.platform.OidcPages;
import com.cdwy.permission.platform.AccountNaming;
import com.cdwy.permission.platform.PlatformStore;
import com.cdwy.permission.service.PasswordPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;
import org.springframework.security.web.csrf.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepresentationSecurityTests {
    @ParameterizedTest @CsvSource({"200,SUCCESS", "400,INVALID_ARGUMENT", "401,UNAUTHENTICATED", "403,FORBIDDEN", "404,NOT_FOUND", "409,CONFLICT", "500,SERVER_ERROR"})
    void v2RepresentationMapsStatusAndNeverExposesFailureData(int status, String code) {
        var request = new MockHttpServletRequest("GET", "/api/users");
        request.addHeader("Accept", "application/vnd.cdwy.v2+json");
        var response = new MockHttpServletResponse(); response.setStatus(status);
        var payload = Map.of("message", "业务结果", "id", 7);
        var result = (Map<?,?>) new ApiEnvelopeAdvice().beforeBodyWrite(payload, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response));
        assertEquals(code, result.get("code")); assertEquals("业务结果", result.get("message"));
        if (status < 400) assertEquals(payload, result.get("data")); else assertNull(result.get("data"));
        assertEquals("Accept", response.getHeader("Vary"));
    }
    @Test void envelopePreservesLegacyNonApiAndNoContentResponses() {
        var advice = new ApiEnvelopeAdvice(); var payload = List.of("record");
        var request = new MockHttpServletRequest("GET", "/api/users");
        var response = new MockHttpServletResponse();
        assertSame(payload, advice.beforeBodyWrite(payload, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response)));
        request.addHeader("Accept", "application/vnd.cdwy.v2+json"); response.setStatus(204);
        assertNull(advice.beforeBodyWrite(null, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response)));
        response.setStatus(200); request.setRequestURI("/userinfo");
        assertSame(payload, advice.beforeBodyWrite(payload, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response)));
        request.setRequestURI("/api/users");
        var success = (Map<?,?>) advice.beforeBodyWrite(payload, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response));
        assertEquals("成功", success.get("message"));
        response.setStatus(500);
        assertEquals("请求失败", ((Map<?,?>) advice.beforeBodyWrite(null, null, MediaType.APPLICATION_JSON, null,
            new ServletServerHttpRequest(request), new ServletServerHttpResponse(response))).get("message"));
    }
    @Test void oidcPagesEscapeUntrustedClientScopeStateAndCsrfValues() {
        var pages = new OidcPages(); var request = new MockHttpServletRequest();
        assertFalse(pages.login(request).contains("role='alert'"));
        request.addParameter("error", "true");
        request.setAttribute(CsrfToken.class.getName(), new DefaultCsrfToken("X-CSRF", "_csrf", "token'\"<>"));
        assertTrue(pages.login(request).contains("role='alert'"));
        request.addParameter("client_id", "<script>alert(1)</script>");
        request.addParameter("state", "' autofocus onfocus='alert(1)");
        request.addParameter("scope", "openid profile <script>");
        String html = pages.consent(request);
        assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("value='openid'")); assertTrue(html.contains("value='profile'"));
        assertFalse(html.contains("value='' autofocus"));
        assertFalse(pages.consent(new MockHttpServletRequest()).contains("value='null'"));
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "Short1!", "abcdefgh", "12345678", "abcd1234", "ABCD1234"})
    void weakPasswordsAreRejected(String password) {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(password));
    }
    @Test void passwordPolicyCountsUtf8BytesAndAcceptsThreeCharacterCategories() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Ab1" + "x".repeat(69)));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Ab1" + "x".repeat(70)));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Ab1" + "中".repeat(24)));
        assertDoesNotThrow(() -> PasswordPolicy.validate("lower123!"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("UPPER123!"));
    }
    @Test void namingHandlesLatinChineseWhitespaceCollisionsAndManualReview() {
        var store = mock(PlatformStore.class); var naming = new AccountNaming(store);
        assertEquals("alice1", naming.generate(" Alice1 ", null).username());
        assertEquals("zs", naming.generate("张三", null).username());
        assertTrue(naming.generate("单", null).manualReview());
        when(store.count("select count(*) from sys_account where username=?", "alice")).thenReturn(1L);
        assertThrows(IllegalArgumentException.class, () -> naming.generate("Alice", null));
        assertEquals("alice1234", naming.generate("Alice", "13800001234").username());
        when(store.count("select count(*) from sys_account where username=?", "alice1234")).thenReturn(1L);
        var collision = naming.generate("Alice", "13800001234");
        assertEquals("alice12342", collision.username()); assertTrue(collision.manualReview());
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "😀", "---"})
    void untransliterableNamesNeedExplicitCorrection(String name) {
        assertThrows(IllegalArgumentException.class, () -> new AccountNaming(mock(PlatformStore.class)).generate(name, null));
    }
}
