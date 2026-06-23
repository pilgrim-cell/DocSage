package com.javaee.docmanager.common.utils;

import com.javaee.docmanager.common.exception.TokenException;
import com.javaee.docmanager.common.utils.JwtUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    @Test
    void testGenerateToken() {
        String token = JwtUtils.generateToken(1L, "testuser","USER");

        assertNotNull(token);
        assertTrue(JwtUtils.validateToken(token));
        assertEquals(1L,JwtUtils.getUserId(token));
        assertEquals("testuser",JwtUtils.getUsername(token));
        assertEquals("USER",JwtUtils.getRole(token));
    }

    @Test
    void invalidToken_shouldReturnFail(){
        assertFalse(JwtUtils.validateToken("invalid.token.here"));
    }

    @Test
    void isTokenExpired_validToken_shouldReturnFalse(){
        String token = JwtUtils.generateToken(1L,"alice","USER");
        assertFalse(JwtUtils.isTokenExpired(token));
    }

    @Test
    void extractToken_validBearerToken_shouldReturnToken(){
        String token = JwtUtils.generateToken(1L,"alice","USER");
        String extracted = JwtUtils.extractToken("Bearer " + token);
        assertEquals(token,extracted);
    }

    @Test
    void parseToken_tamperedToken_shouldThrow(){
        assertThrows(TokenException.class,() -> JwtUtils.parseToken("eyJhbGciOiJIUzI1NiJ9.invalid.signature"));
    }

}
