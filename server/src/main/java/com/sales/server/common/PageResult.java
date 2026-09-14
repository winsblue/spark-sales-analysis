package com.sales.server.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果封装。
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页码（从 1 开始） */
    private long pageNum;

    /** 每页条数 */
    private long pageSize;

    /** 总记录数 */
    private long total;

    /** 总页数 */
    private long pages;

    /** 当前页数据 */
    private List<T> records;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> r = new PageResult<>();
        r.setPageNum(page.getCurrent());
        r.setPageSize(page.getSize());
        r.setTotal(page.getTotal());
        r.setPages(page.getPages());
        r.setRecords(page.getRecords());
        return r;
    }

    public static <T> PageResult<T> of(long pageNum, long pageSize, long total, List<T> records) {
        PageResult<T> r = new PageResult<>();
        r.setPageNum(pageNum);
        r.setPageSize(pageSize);
        r.setTotal(total);
        r.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        r.setRecords(records);
        return r;
    }
}
