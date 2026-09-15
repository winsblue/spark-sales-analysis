package com.sales.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sales.server.common.BizException;
import com.sales.server.common.PageResult;
import com.sales.server.dto.AnalysisQuery;
import com.sales.server.entity.DwdOrderDetail;
import com.sales.server.mapper.DwdOrderDetailMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订单明细服务：明细下钻分页查询与数据导出。
 */
@Slf4j
@Service
public class OrderService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] EXPORT_HEADERS = {
            "订单编号", "下单时间", "用户编号", "商品编号", "商品名称", "类目", "品牌",
            "渠道", "省份", "城市", "数量", "单价", "优惠金额", "实付金额",
            "订单状态", "支付方式", "是否有效订单"
    };

    @Resource
    private DwdOrderDetailMapper orderMapper;

    /** 明细下钻：物理分页查询 */
    public PageResult<DwdOrderDetail> page(AnalysisQuery query) {
        query.normalize();
        long current = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        Page<DwdOrderDetail> page = new Page<>(current, query.pageSizeOrDefault());
        Page<DwdOrderDetail> result = orderMapper.selectPage(page, buildWrapper(query));
        return PageResult.of(result);
    }

    /** 数据导出：按筛选条件导出 CSV */
    public void exportCsv(AnalysisQuery query, OutputStream out) {
        query.normalize();
        LambdaQueryWrapper<DwdOrderDetail> wrapper = buildWrapper(query)
                .last("LIMIT " + query.exportLimitOrDefault());
        List<DwdOrderDetail> list = orderMapper.selectList(wrapper);
        if (list.isEmpty()) {
            log.warn("导出条件无匹配数据，仍输出仅含表头的文件");
        }
        try {
            // 写入 UTF-8 BOM，保证 Excel 打开中文不乱码
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(String.join(",", EXPORT_HEADERS));
            writer.newLine();
            for (DwdOrderDetail d : list) {
                StringBuilder sb = new StringBuilder();
                append(sb, d.getOrderId());
                append(sb, d.getOrderTime() == null ? "" : d.getOrderTime().format(DTF));
                append(sb, d.getUserId());
                append(sb, d.getProductId());
                append(sb, d.getProductName());
                append(sb, d.getCategoryName());
                append(sb, d.getBrand());
                append(sb, d.getChannel());
                append(sb, d.getProvince());
                append(sb, d.getCity());
                append(sb, d.getQuantity() == null ? "" : String.valueOf(d.getQuantity()));
                append(sb, plain(d.getUnitPrice()));
                append(sb, plain(d.getDiscountAmount()));
                append(sb, plain(d.getPayAmount()));
                append(sb, d.getOrderStatus());
                append(sb, d.getPayType());
                appendLast(sb, d.getIsValidOrder() != null && d.getIsValidOrder() == 1 ? "有效" : "无效");
                writer.write(sb.toString());
                writer.newLine();
            }
            writer.flush();
            log.info("明细导出完成，共 {} 条", list.size());
        } catch (IOException e) {
            throw new BizException(500, "导出失败：" + e.getMessage());
        }
    }

    /** 构造多条件查询：日期范围 + 渠道 + 类目 + 省份 + 支付方式 + 订单状态 */
    private LambdaQueryWrapper<DwdOrderDetail> buildWrapper(AnalysisQuery q) {
        LambdaQueryWrapper<DwdOrderDetail> w = Wrappers.lambdaQuery();
        w.ge(isNotBlank(q.getStartDate()), DwdOrderDetail::getStatDate, q.getStartDate())
                .le(isNotBlank(q.getEndDate()), DwdOrderDetail::getStatDate, q.getEndDate())
                .eq(isNotBlank(q.getChannel()), DwdOrderDetail::getChannel, q.getChannel())
                .eq(isNotBlank(q.getCategoryName()), DwdOrderDetail::getCategoryName, q.getCategoryName())
                .eq(isNotBlank(q.getProvince()), DwdOrderDetail::getProvince, q.getProvince())
                .eq(isNotBlank(q.getPayType()), DwdOrderDetail::getPayType, q.getPayType())
                .eq(isNotBlank(q.getOrderStatus()), DwdOrderDetail::getOrderStatus, q.getOrderStatus())
                .eq(q.getOnlyValid() != null && q.getOnlyValid() == 1,
                        DwdOrderDetail::getIsValidOrder, 1)
                // 主键自增，且明细按时间顺序写入，因此"按 id 倒序"等价于"按下单时间倒序"，
                // 但可以直接走主键索引，避免对 19 万行做 filesort
                .orderByDesc(DwdOrderDetail::getId);
        return w;
    }

    private static void append(StringBuilder sb, String v) {
        sb.append(csv(v)).append(',');
    }

    private static void appendLast(StringBuilder sb, String v) {
        sb.append(csv(v));
    }

    /** CSV 转义：字段中含逗号、引号或换行时用双引号包裹 */
    private static String csv(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
