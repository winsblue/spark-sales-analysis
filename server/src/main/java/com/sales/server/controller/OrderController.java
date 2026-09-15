package com.sales.server.controller;

import com.sales.server.common.PageResult;
import com.sales.server.common.Result;
import com.sales.server.dto.AnalysisQuery;
import com.sales.server.entity.DwdOrderDetail;
import com.sales.server.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.api.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;

/**
 * 订单明细接口：明细下钻与数据导出。
 */
@Tag(name = "02-订单明细", description = "明细下钻分页查询与 CSV 导出")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Operation(summary = "明细分页查询", description = "支持日期范围、渠道、类目、省份、支付方式、订单状态多条件筛选")
    @GetMapping("/page")
    public Result<PageResult<DwdOrderDetail>> page(@ParameterObject AnalysisQuery query) {
        return Result.success(orderService.page(query));
    }

    @Operation(summary = "明细数据导出", description = "按当前筛选条件导出 CSV 文件（含 UTF-8 BOM，Excel 打开不乱码）")
    @GetMapping("/export")
    public void export(@ParameterObject AnalysisQuery query, HttpServletResponse response) throws IOException {
        String fileName = URLEncoder.encode("订单明细导出.csv", "UTF-8").replace("+", "%20");
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        orderService.exportCsv(query, response.getOutputStream());
        response.flushBuffer();
    }
}
