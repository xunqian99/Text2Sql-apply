package com.ecommerce.platform.product;

import com.ecommerce.platform.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品数据访问仓储层。
 * 基于 Spring JdbcTemplate 执行高效安全的参数化查询。
 */
@Repository
public class ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Product> productRowMapper = (rs, rowNum) -> new Product(
            rs.getString("product_id"),
            rs.getString("product_category_name"),
            rs.getObject("product_photos_qty") != null ? rs.getInt("product_photos_qty") : null,
            rs.getObject("product_weight_g") != null ? rs.getInt("product_weight_g") : null,
            rs.getObject("product_length_cm") != null ? rs.getInt("product_length_cm") : null,
            rs.getObject("product_height_cm") != null ? rs.getInt("product_height_cm") : null,
            rs.getObject("product_width_cm") != null ? rs.getInt("product_width_cm") : null,
            rs.getObject("avg_price") != null ? rs.getBigDecimal("avg_price") : null
    );

    /**
     * 分页多条件检索商品列表。
     */
    public PageResult<Product> findPage(String category, String keyword, int page, int pageSize) {
        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (category != null && !category.isBlank()) {
            whereClause.append(" AND p.product_category_name = ? ");
            params.add(category.trim());
        }

        if (keyword != null && !keyword.isBlank()) {
            whereClause.append(" AND (p.product_id LIKE ? OR p.product_category_name LIKE ?) ");
            String likeVal = "%" + keyword.trim() + "%";
            params.add(likeVal);
            params.add(likeVal);
        }

        // 统计总数
        String countSql = "SELECT COUNT(*) FROM products p " + whereClause;
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalCount = total != null ? total : 0;

        if (totalCount == 0) {
            return PageResult.of(List.of(), 0, page, pageSize);
        }

        // 分页查询明细并关联均价
        int offset = Math.max(0, (page - 1) * pageSize);
        String querySql = "SELECT p.*, "
                + " (SELECT ROUND(AVG(price), 2) FROM order_items oi WHERE oi.product_id = p.product_id) AS avg_price "
                + " FROM products p "
                + whereClause
                + " ORDER BY p.product_id ASC "
                + " LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(pageSize);
        queryParams.add(offset);

        List<Product> list = jdbcTemplate.query(querySql, productRowMapper, queryParams.toArray());
        return PageResult.of(list, totalCount, page, pageSize);
    }

    /**
     * 根据 ID 查询单个商品详情。
     */
    public Optional<Product> findById(String productId) {
        String sql = "SELECT p.*, "
                + " (SELECT ROUND(AVG(price), 2) FROM order_items oi WHERE oi.product_id = p.product_id) AS avg_price "
                + " FROM products p WHERE p.product_id = ?";
        List<Product> list = jdbcTemplate.query(sql, productRowMapper, productId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /**
     * 新增商品。
     */
    public int create(Product product) {
        String sql = "INSERT INTO products (product_id, product_category_name, product_photos_qty, "
                + "product_weight_g, product_length_cm, product_height_cm, product_width_cm) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        return jdbcTemplate.update(sql,
                product.productId(),
                product.productCategoryName(),
                product.productPhotosQty(),
                product.productWeightG(),
                product.productLengthCm(),
                product.productHeightCm(),
                product.productWidthCm());
    }

    /**
     * 更新商品信息。
     */
    public int update(Product product) {
        String sql = "UPDATE products SET product_category_name = ?, product_photos_qty = ?, "
                + "product_weight_g = ?, product_length_cm = ?, product_height_cm = ?, product_width_cm = ? "
                + "WHERE product_id = ?";
        return jdbcTemplate.update(sql,
                product.productCategoryName(),
                product.productPhotosQty(),
                product.productWeightG(),
                product.productLengthCm(),
                product.productHeightCm(),
                product.productWidthCm(),
                product.productId());
    }

    /**
     * 删除指定商品。
     */
    public int delete(String productId) {
        String sql = "DELETE FROM products WHERE product_id = ?";
        return jdbcTemplate.update(sql, productId);
    }

    /**
     * 获取所有可用的商品类目列表（供前端下拉筛选）。
     */
    public List<String> listCategories() {
        String sql = "SELECT DISTINCT product_category_name FROM products "
                + "WHERE product_category_name IS NOT NULL AND product_category_name <> '' "
                + "ORDER BY product_category_name ASC LIMIT 100";
        return jdbcTemplate.queryForList(sql, String.class);
    }
}
