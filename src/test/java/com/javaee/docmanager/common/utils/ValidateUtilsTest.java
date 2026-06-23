package com.javaee.docmanager.common.utils;

import com.javaee.docmanager.common.exception.BusinessException;
import com.javaee.docmanager.common.utils.ValidateUtils;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ValidateUtilsTest {

    @Test
    void notEmpty_validString_shouldNotThrow(){
        assertDoesNotThrow(() -> ValidateUtils.notEmpty("test","不能为空"));
    }

    @Test
    void notEmpty_emptyString_shouldThrow(){
        BusinessException exception = assertThrows(BusinessException.class,() -> ValidateUtils.notEmpty("","用户名不能为空"));
        assertEquals("用户名不能为空",exception.getMessage());
    }

    @Test
    void notEmpty_emptyCollection_shouldThrow(){
        List<String> list = Collections.emptyList();
        assertThrows(BusinessException.class,
        () -> ValidateUtils.notEmpty(list,"列表不能为空"));
    }

    @Test
    void email_validateUtils_shouldNotThrow(){
        assertDoesNotThrow(() -> ValidateUtils.email("user@example.com"));
    }

    @Test
    void email_invalidEmail_shouldThrow(){
        assertThrows(BusinessException.class,
        () -> ValidateUtils.email("not-email"));
    }

    @Test
    void phone_validateUtils_shouldNotThrow(){
        assertDoesNotThrow(() -> ValidateUtils.phone("13345678901"));
    }

    @Test
    void phone_invalid_shouldThrow(){
        assertThrows(BusinessException.class,
        () -> ValidateUtils.phone("1234"));
    }

    @Test
    void password_valid_shouldPass() {
        assertDoesNotThrow(() -> ValidateUtils.password("Abc12345!"));
    }
    @Test
    void password_missingSpecialChar_shouldThrow() {
        assertThrows(BusinessException.class,
                () -> ValidateUtils.password("Abc12345"));
    }

    @Test
    void length_withinRange_shouldPass() {
        assertDoesNotThrow(() -> ValidateUtils.length("abc", 1, 10));
    }
    @Test
    void length_outOfRange_shouldThrow() {
        assertThrows(BusinessException.class,
                () -> ValidateUtils.length("ab", 3, 10));
    }
    @Test
    void range_validNumber_shouldPass() {
        assertDoesNotThrow(() -> ValidateUtils.range(5, 1, 10));
    }
    @Test
    void range_outOfRange_shouldThrow() {
        assertThrows(BusinessException.class,
                () -> ValidateUtils.range(0, 1, 10));
    }
    @Test
    void isTrue_falseCondition_shouldThrow() {
        assertThrows(BusinessException.class,
                () -> ValidateUtils.isTrue(false, "条件不满足"));
    }
}
