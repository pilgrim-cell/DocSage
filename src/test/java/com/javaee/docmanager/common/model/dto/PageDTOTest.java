package com.javaee.docmanager.common.model.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.javaee.docmanager.common.model.dto.PageDTO;
import com.javaee.docmanager.common.constant.CommonConstant;

class PageDTOTest {
    @Test
    void defualtConstructor_shouldUseDefaultPageValues(){
        PageDTO pageDTO = new PageDTO();

        assertEquals(CommonConstant.PAGE_NUM,pageDTO.getPageNum());
        assertEquals(CommonConstant.PAGE_SIZE,pageDTO.getPageSize());
        assertEquals("asc",pageDTO.getSortOrder());
    }

    @Test
    void constructor_invalidPageValues_shouldUseDefaultValues(){
        PageDTO pageDTO = new PageDTO(-1,20);

        assertEquals(CommonConstant.PAGE_NUM,pageDTO.getPageNum());
        assertEquals(20,pageDTO.getPageSize());
    }

    @Test
    void constructor_invalidPageSize_shouldUseMaxPageSize(){
        PageDTO pageDTO = new PageDTO(1,999);

        assertEquals(1,pageDTO.getPageNum());
        assertEquals(CommonConstant.MAX_PAGE_SIZE,pageDTO.getPageSize());
    }

    @Test
    void setPageSize_invalidValue_shouldCapATMax(){
        PageDTO pageDTO = new PageDTO();
        pageDTO.setPageSize(500);

        assertEquals(CommonConstant.MAX_PAGE_SIZE,pageDTO.getPageSize());
    }

    @Test
    void getOffset_shouldCalculateCorrectly(){
        PageDTO pageDTO = new PageDTO(3,10);

        assertEquals(20,pageDTO.getOffset());
    }

    @Test
    void setSortOrder_null_shouldDefaultToAsc(){
        PageDTO pageDTO = new PageDTO(1,10,"createTime",null);

        assertEquals("asc",pageDTO.getSortOrder());
    }
}
