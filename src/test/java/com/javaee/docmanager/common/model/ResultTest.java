package com.javaee.docmanager.common.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.common.constant.ErrorCodeEnum;

class ResultTest {
    @Test
    void success_withData_shouldRuturn200(){
        Result<String> result = Result.success("hello");

        assertEquals(200,result.getCode());
        assertEquals("操作成功",result.getMessage());
        assertEquals("hello",result.getData());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void success_withoutData_shouldRuturn200AndNullData(){
        Result<Void> result = Result.success();

        assertEquals(200,result.getCode());
        assertEquals("操作成功",result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void success_withMessageAndData(){
        Result<Integer> result = Result.success("查询成功",42);

        assertEquals(200,result.getCode());
        assertEquals("查询成功",result.getMessage());
        assertEquals(42,result.getData());
    }

    @Test
    void fail_withErrorCodeEnum_shouldMatchEnum(){
        Result<Void> result = Result.fail(ErrorCodeEnum.USER_NOT_FOUND);

        assertEquals(606,result.getCode());
        assertEquals("用户不存在",result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void fail_withMessage_shouldUseBusinessError(){
        Result<Void> result = Result.fail("自定义错误");

        assertEquals(600,result.getCode());
        assertEquals("自定义错误",result.getMessage());
    }
}
