package com.ecommerce.platform.order;

import com.ecommerce.platform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 订单数据访问仓储层。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<OrderSummary> orderRowMapper = (rs, rowNum) -> {
        Timestamp purchaseTs = rs.getTimestamp("order_purchase_timestamp");
        Timestamp deliveredTs = rs.getTimestamp("order_delivered_customer_date");
        return new OrderSummary(
                rs.getString("order_id"),
                rs.getString("customer_id"),
                rs.getString("order_status"),
                purchaseTs != null ? purchaseTs.toLocalDateTime() : null,
                deliveredTs != null ? deliveredTs.toLocalDateTime() : null,
                rs.getObject("item_count") != null ? rs.getInt("item_count") : 0,
                rs.getBigDecimal("total_amount")
        );
    };

    /**
     * 分页查询订单。
     */
    public PageResult<OrderSummary> findPage(String status, String orderId, int page, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            where.append(" AND o.order_status = ? ");
            params.add(status.trim());
        }

        if (orderId != null && !orderId.isBlank()) {
            where.append(" AND o.order_id LIKE ? ");
            params.add("%" + orderId.trim() + "%");
        }

        String countSql = "SELECT COUNT(*) FROM orders o " + where;
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalCount = total != null ? total : 0;

        if (totalCount == 0) {
            return PageResult.of(List.of(), 0, page, pageSize);
        }

        int offset = Math.max(0, (page - 1) * pageSize);
        String sql = "SELECT o.order_id, o.customer_id, o.order_status, o.order_purchase_timestamp, "
                + "o.order_delivered_customer_date, "
                + "(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count, "
                + "(SELECT COALESCE(SUM(price + freight_value), 0) FROM order_items oi WHERE oi.order_id = o.order_id) AS total_amount "
                + "FROM orders o "
                + where
                + "ORDER BY o.order_purchase_timestamp DESC NULLS LAST "
                + "LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        List<OrderSummary> list = jdbcTemplate.query(sql, orderRowMapper, queryParams.toArray());
        return PageResult.of(list, totalCount, page, pageSize);
    }

    /**
     * 根据订单 ID 查找。
     */
    public Optional<OrderSummary> findById(String orderId) {
        String sql = "SELECT o.order_id, o.customer_id, o.order_status, o.order_purchase_timestamp, "
                + "o.order_delivered_customer_date, "
                + "(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count, "
                + "(SELECT COALESCE(SUM(price + freight_value), 0) FROM order_items oi WHERE oi.order_id = o.order_id) AS total_amount "
                + "FROM orders o WHERE o.order_id = ?";
        List<OrderSummary> list = jdbcTemplate.query(sql, orderRowMapper, orderId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /**
     * 更新订单状态（如发货、取消等）。
     */
    public int updateStatus(String orderId, String newStatus) {
        String sql = "UPDATE orders SET order_status = ? WHERE order_id = ?";
        return jdbcTemplate.update(sql, newStatus, orderId);
    }
}
