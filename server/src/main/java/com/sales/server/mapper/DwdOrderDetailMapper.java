package com.sales.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sales.server.entity.DwdOrderDetail;

/**
 * 订单明细 Mapper。
 *
 * <p>明细下钻与数据导出均为"按筛选条件分页/限量查询"，
 * 使用 MyBatis-Plus 的条件构造器即可完成，无需额外编写 XML。</p>
 */
public interface DwdOrderDetailMapper extends BaseMapper<DwdOrderDetail> {
}
