package com.evops.rectify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.rectify.entity.RectifyOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RectifyOrderMapper extends BaseMapper<RectifyOrder> {
    /** 整改单号全局序列，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_rectify_no")
    long nextRectifySeq();
}
