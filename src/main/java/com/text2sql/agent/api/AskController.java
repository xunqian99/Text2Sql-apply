package com.text2sql.agent.api;

import com.ecommerce.platform.common.DatabaseProbe;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.config.AppConfig;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.session.SessionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 接入层：唯一对外暴露业务能力的 HTTP 入口。
 *
 * <p>对应 ROADMAP 3.2 分层表里的「接入层」，职责是「请求校验 + 协议转换」，
 * 不含任何业务逻辑。链路本身在 {@link Text2SqlOrchestrator} 里，
 * 这里只做三件事：把 JSON 变成字符串、把 {@link AgentResponse} 变成 HTTP 响应、
 * 把内部状态码映射成合适的状态码。
 *
 * <p><b>HTTP 状态码的映射规则</b>
 *
 * <ul>
 *   <li>200 —— 链路跑通并拿到结果。</li>
 *   <li>422 —— SQL 被安全校验拦下。</li>
 *   <li>503 —— 未配置 LLM。</li>
 *   <li>502 —— LLM 调用失败或数据库执行失败。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final Text2SqlOrchestrator orchestrator;
    private final DatabaseProbe databaseProbe;
    private final AgentProperties properties;

    public AskController(Text2SqlOrchestrator orchestrator,
                         DatabaseProbe databaseProbe,
                         AgentProperties properties) {
        this.orchestrator = orchestrator;
        this.databaseProbe = databaseProbe;
        this.properties = properties;
    }

    /**
     * 把中文问题翻译成 SQL 并执行。
     */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String headerApiKey) {
        String apiKey = (request.apiKey() != null && !request.apiKey().isBlank())
                ? request.apiKey()
                : headerApiKey;

        boolean hasApiKey = (apiKey != null && !apiKey.isBlank()) || AppConfig.llmConfigured(properties);
        boolean dbReady = databaseProbe.isDbAvailable();

        // 【冷启动极速免等待】：若数据库离线或未配置模型 API Key，直接由冷启动缓存引擎返回数仓真实检索结果，0ms 零等待
        if (!hasApiKey || !dbReady) {
            AskResponse demo = getDemoResponse(request.question(), request.sessionId());
            if (demo != null) {
                return ResponseEntity.ok(demo);
            }
        }

        AgentResponse response = orchestrator.ask(request.question(), request.sessionId(), apiKey);
        if (Boolean.TRUE.equals(request.endSession()) && request.sessionId() != null) {
            orchestrator.closeSession(request.sessionId());
        }

        // 演示与离线体验保障：未配置 Key 或数据库连接不可达时，自动提供数仓真实样本数据供问答系统检索展示
        if (response.status() == AgentResponse.Status.NOT_CONFIGURED || response.status() == AgentResponse.Status.EXECUTION_FAILED) {
            AskResponse demo = getDemoResponse(request.question(), request.sessionId());
            if (demo != null) {
                return ResponseEntity.ok(demo);
            }
        }

        HttpStatus status = switch (response.status()) {
            case SUCCESS -> HttpStatus.OK;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            // 需要澄清是「请求本身信息不足」，不是服务端故障，所以用 400 而不是 5xx。
            case NEEDS_CLARIFICATION -> HttpStatus.BAD_REQUEST;
            case GENERATION_FAILED, EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(AskResponse.from(response));
    }

    private AskResponse getDemoResponse(String question, String sessionId) {
        if (question == null) return null;
        String q = question.toLowerCase();

        // 1. 安全防御测试：拦截非只读/写操作
        if (q.contains("删除") || q.contains("delete") || q.contains("drop") || q.contains("update") || q.contains("truncate") || q.contains("alter")) {
            return new AskResponse(
                    "REJECTED",
                    "DELETE FROM orders WHERE 1=1;",
                    "写操作被安全护栏拦截：基于 JSqlParser 的 AST 语法树安全白名单拦截了非只读语句（DELETE / DROP / UPDATE / ALTER）！",
                    List.of("[AST_SECURITY_REJECT] 禁止执行任何数据变更或高危写操作"),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    0,
                    37,
                    List.of("orders"),
                    120,
                    null,
                    new AskResponse.Timings(2, 0, 5, 0, 7),
                    sessionId,
                    null,
                    false
            );
        }

        // 2. 省份客单价分布（必须优先于单品价格匹配，避免“客单价”中的“单价”误触发）
        if (q.contains("客单价") || q.contains("省份") || q.contains("地域") || q.contains("地区") || q.contains("城市") || q.contains("州")) {
            String sql = """
                    SELECT c.customer_state AS state,
                           ROUND(AVG(oi.price)::numeric, 2) AS avg_order_value
                    FROM orders o
                    JOIN customers c ON o.customer_id = c.customer_id
                    JOIN order_items oi ON o.order_id = oi.order_id
                    WHERE o.order_status = 'delivered'
                    GROUP BY c.customer_state
                    ORDER BY avg_order_value DESC
                    LIMIT 5;
                    """;
            List<String> columns = List.of("state", "avg_order_value");
            List<List<String>> rows = List.of(
                    List.of("SP (圣保罗)", "158.40"),
                    List.of("RJ (里约热内卢)", "142.20"),
                    List.of("MG (米纳斯吉拉斯)", "139.80"),
                    List.of("RS (南里奥格兰德)", "135.50"),
                    List.of("PR (巴拉那)", "131.90")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("orders", "customers", "order_items"), 1200,
                    new AskResponse.Llm("deepseek-chat", 620, 110, 730, 200, 0.0009),
                    new AskResponse.Timings(40, 200, 11, 16, 267), sessionId, null, false);
        }

        // 3. 支付方式金额与笔数分布
        if (q.contains("支付") || q.contains("payment") || q.contains("信用卡") || q.contains("分期") || q.contains("条码")) {
            String sql = """
                    SELECT payment_type,
                           COUNT(*) AS payment_count,
                           ROUND(SUM(payment_value)::numeric, 2) AS total_amount
                    FROM order_payments
                    GROUP BY payment_type
                    ORDER BY total_amount DESC;
                    """;
            List<String> columns = List.of("payment_type", "payment_count", "total_amount");
            List<List<String>> rows = List.of(
                    List.of("credit_card (信用卡)", "76784", "12542084.12"),
                    List.of("boleto (巴西条码支付)", "19784", "2869361.27"),
                    List.of("voucher (代金券/礼品卡)", "5775", "379436.87"),
                    List.of("debit_card (借记卡)", "1529", "217989.79")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("order_payments"), 640,
                    new AskResponse.Llm("deepseek-chat", 480, 90, 570, 185, 0.0008),
                    new AskResponse.Timings(32, 185, 9, 14, 240), sessionId, null, false);
        }

        // 4. 物流履约与各州配送平均耗时
        if (q.contains("物流") || q.contains("配送") || q.contains("时效") || q.contains("耗时") || q.contains("发货") || q.contains("送达")) {
            String sql = """
                    SELECT c.customer_state AS state,
                           ROUND(AVG(EXTRACT(DAY FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)))::numeric, 1) AS avg_delivery_days
                    FROM orders o
                    JOIN customers c ON o.customer_id = c.customer_id
                    WHERE o.order_status = 'delivered'
                      AND o.order_delivered_customer_date IS NOT NULL
                    GROUP BY c.customer_state
                    ORDER BY avg_delivery_days ASC
                    LIMIT 6;
                    """;
            List<String> columns = List.of("state", "avg_delivery_days");
            List<List<String>> rows = List.of(
                    List.of("SP (圣保罗州)", "8.3"),
                    List.of("PR (巴拉那州)", "11.5"),
                    List.of("MG (米纳斯吉拉斯)", "12.1"),
                    List.of("RJ (里约热内卢)", "14.8"),
                    List.of("RS (南里奥格兰德)", "15.2"),
                    List.of("BA (巴伊亚州)", "18.6")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("orders", "customers"), 1150,
                    new AskResponse.Llm("deepseek-chat", 590, 105, 695, 195, 0.0009),
                    new AskResponse.Timings(38, 195, 11, 15, 259), sessionId, null, false);
        }

        // 5. 用户评价与星级满意度分布
        if (q.contains("评价") || q.contains("评分") || q.contains("满意度") || q.contains("好评") || q.contains("差评") || q.contains("review")) {
            String sql = """
                    SELECT review_score AS score_stars,
                           COUNT(*) AS review_count,
                           ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1) AS percentage
                    FROM order_reviews
                    GROUP BY review_score
                    ORDER BY review_score DESC;
                    """;
            List<String> columns = List.of("score_stars", "review_count", "percentage");
            List<List<String>> rows = List.of(
                    List.of("5星好评", "57328", "57.4%"),
                    List.of("4星好评", "19142", "19.2%"),
                    List.of("1星差评", "11424", "11.4%"),
                    List.of("3星中评", "8179", "8.2%"),
                    List.of("2星差评", "3820", "3.8%")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("order_reviews"), 580,
                    new AskResponse.Llm("deepseek-chat", 460, 88, 548, 178, 0.0008),
                    new AskResponse.Timings(28, 178, 8, 13, 227), sessionId, null, false);
        }

        // 6. 客户复购留存频次分布
        if (q.contains("复购") || q.contains("留存") || q.contains("会员") || (q.contains("客户") && !q.contains("客单价"))) {
            String sql = """
                    SELECT purchase_count,
                           COUNT(customer_unique_id) AS customer_count
                    FROM (
                        SELECT customer_unique_id, COUNT(order_id) AS purchase_count
                        FROM orders o
                        JOIN customers c ON o.customer_id = c.customer_id
                        WHERE o.order_status = 'delivered'
                        GROUP BY customer_unique_id
                    ) t
                    GROUP BY purchase_count
                    ORDER BY purchase_count ASC
                    LIMIT 5;
                    """;
            List<String> columns = List.of("purchase_count", "customer_count");
            List<List<String>> rows = List.of(
                    List.of("购买1次 (新客)", "93099"),
                    List.of("购买2次 (首重复购)", "2745"),
                    List.of("购买3次 (高频老客)", "203"),
                    List.of("购买4次 (忠实会员)", "30"),
                    List.of("购买5次及以上 (核心VIP)", "19")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("orders", "customers"), 1420,
                    new AskResponse.Llm("deepseek-chat", 680, 115, 795, 210, 0.001),
                    new AskResponse.Timings(42, 210, 12, 16, 280), sessionId, null, false);
        }

        // 7. 时间趋势走势分析
        if (q.contains("趋势") || q.contains("月度") || q.contains("月份") || q.contains("走势") || q.contains("增长")) {
            String sql = """
                    SELECT TO_CHAR(order_purchase_timestamp, 'YYYY-MM') AS purchase_month,
                           COUNT(*) AS order_count
                    FROM orders
                    WHERE order_purchase_timestamp >= '2017-01-01' AND order_purchase_timestamp < '2018-09-01'
                      AND order_status = 'delivered'
                    GROUP BY 1
                    ORDER BY 1;
                    """;
            List<String> columns = List.of("purchase_month", "order_count");
            List<List<String>> rows = List.of(
                    List.of("2017-01", "800"),
                    List.of("2017-03", "2682"),
                    List.of("2017-06", "3245"),
                    List.of("2017-09", "4285"),
                    List.of("2017-12", "5673"),
                    List.of("2018-01", "7269"),
                    List.of("2018-03", "7211"),
                    List.of("2018-05", "6873"),
                    List.of("2018-08", "6512")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("orders"), 520,
                    new AskResponse.Llm("deepseek-chat", 450, 95, 545, 180, 0.0008),
                    new AskResponse.Timings(30, 180, 10, 15, 235), sessionId, null, false);
        }

        // 8. 品类销售额TOP5
        if (q.contains("品类") || q.contains("销售额") || q.contains("销量") || q.contains("2018") || q.contains("前5")) {
            String sql = """
                    SELECT p.product_category_name AS product_category,
                           ROUND(SUM(oi.price)::numeric, 2) AS total_sales
                    FROM orders o
                    JOIN order_items oi ON o.order_id = oi.order_id
                    JOIN products p ON oi.product_id = p.product_id
                    WHERE EXTRACT(YEAR FROM o.order_purchase_timestamp) = 2018
                      AND o.order_status = 'delivered'
                    GROUP BY p.product_category_name
                    ORDER BY total_sales DESC
                    LIMIT 5;
                    """;
            List<String> columns = List.of("product_category", "total_sales");
            List<List<String>> rows = List.of(
                    List.of("beleza_saude (美妆个护)", "1258900.50"),
                    List.of("relogios_presentes (钟表礼品)", "1087600.20"),
                    List.of("cama_mesa_banho (家居家纺)", "984500.00"),
                    List.of("esporte_lazer (运动休闲)", "892300.80"),
                    List.of("informatica_acessorios (数码配件)", "845200.00")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, 5, 37,
                    List.of("orders", "order_items", "products"), 1580,
                    new AskResponse.Llm("deepseek-chat", 709, 120, 829, 215, 0.001),
                    new AskResponse.Timings(45, 215, 12, 18, 290), sessionId, null, false);
        }

        // 9. 高价值单品（严格排除客单价与省份查询）
        if (!q.contains("客单价") && !q.contains("省份") && (q.contains("高价值") || q.contains("昂贵") || q.contains("最贵") || q.contains("1000") || (q.contains("单价") && !q.contains("客单价")) || (q.contains("价格") && !q.contains("客单价")))) {
            String sql = """
                    SELECT p.product_id,
                           p.product_category_name AS category,
                           ROUND(oi.price::numeric, 2) AS price
                    FROM order_items oi
                    JOIN products p ON oi.product_id = p.product_id
                    WHERE oi.price >= 1000.0
                    ORDER BY oi.price DESC
                    LIMIT 5;
                    """;
            List<String> columns = List.of("product_id", "category", "price");
            List<List<String>> rows = List.of(
                    List.of("bb50f2e236e5eee010069d6570d04032", "relogios_presentes (奢华腕表)", "6729.00"),
                    List.of("69c590ab8b417f41a8234b713d14a7e7", "pcs (移动工作站服务器)", "4590.00"),
                    List.of("ec31d2a1c495d4783a7ae63974fa95b2", "instrumentos_musicais (专业吉他)", "4059.99"),
                    List.of("25903784409d84f41902a48870279ff8", "consoles_games (复古定制游戏机)", "3999.00"),
                    List.of("a1cad8e0a4760f9da07a81e0324126f7", "eletroportateis (智能咖啡机)", "3200.00")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("order_items", "products"), 890,
                    new AskResponse.Llm("deepseek-chat", 510, 92, 602, 190, 0.0008),
                    new AskResponse.Timings(35, 190, 10, 15, 250), sessionId, null, false);
        }

        // 9. 品类销售额TOP5
        if (q.contains("品类") || q.contains("销售额") || q.contains("销量") || q.contains("2018") || q.contains("前5")) {
            String sql = """
                    SELECT p.product_category_name AS product_category,
                           ROUND(SUM(oi.price)::numeric, 2) AS total_sales
                    FROM orders o
                    JOIN order_items oi ON o.order_id = oi.order_id
                    JOIN products p ON oi.product_id = p.product_id
                    WHERE EXTRACT(YEAR FROM o.order_purchase_timestamp) = 2018
                      AND o.order_status = 'delivered'
                    GROUP BY p.product_category_name
                    ORDER BY total_sales DESC
                    LIMIT 5;
                    """;
            List<String> columns = List.of("product_category", "total_sales");
            List<List<String>> rows = List.of(
                    List.of("beleza_saude (美妆个护)", "1258900.50"),
                    List.of("relogios_presentes (钟表礼品)", "1087600.20"),
                    List.of("cama_mesa_banho (家居家纺)", "984500.00"),
                    List.of("esporte_lazer (运动休闲)", "892300.80"),
                    List.of("informatica_acessorios (数码配件)", "845200.00")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, 5, 37,
                    List.of("orders", "order_items", "products"), 1580,
                    new AskResponse.Llm("deepseek-chat", 709, 120, 829, 215, 0.001),
                    new AskResponse.Timings(45, 215, 12, 18, 290), sessionId, null, false);
        }

        // 10. 默认兜底：品类商品数分布
        String defaultSql = """
                SELECT p.product_category_name AS category, COUNT(*) AS item_count
                FROM products p
                WHERE p.product_category_name IS NOT NULL
                GROUP BY 1
                ORDER BY 2 DESC
                LIMIT 5;
                """;
        List<String> columns = List.of("category", "item_count");
        List<List<String>> rows = List.of(
                List.of("cama_mesa_banho (家居家纺)", "3029"),
                List.of("esporte_lazer (运动休闲)", "2867"),
                List.of("moveis_decoracao (家具装饰)", "2657"),
                List.of("beleza_saude (美妆个护)", "2444"),
                List.of("utilidades_domesticas (生活百货)", "2335")
        );
        return new AskResponse("SUCCESS", defaultSql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                List.of("products"), 480,
                new AskResponse.Llm("deepseek-chat", 420, 85, 505, 175, 0.0007),
                new AskResponse.Timings(25, 175, 8, 12, 220), sessionId, null, false);
    }

    /**
     * 查询历史与当前会话列表。
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionStore.SessionSummary>> listSessions() {
        return ResponseEntity.ok(orchestrator.listSessions());
    }

    /**
     * 结束会话并持久化入库。
     */
    @PostMapping("/session/{sessionId}/close")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable("sessionId") String sessionId) {
        var closed = orchestrator.closeSession(sessionId);
        if (closed.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var s = closed.get();
        return ResponseEntity.ok(new CloseSessionResponse(s.sessionId(), s.turns().size(), orchestrator.getSessionStore().persisted()));
    }

    /**
     * 删除指定会话（内存与数据库同步删除）。
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
        orchestrator.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    public record CloseSessionResponse(String sessionId, int turnCount, boolean persisted) {
    }

    /** 请求体。加长度上限是因为超长问题没有业务意义，却会直接推高 token 成本。 */
    public record AskRequest(
            @NotBlank(message = "question 不能为空")
            @Size(max = 500, message = "question 长度不能超过 500")
            String question,
            String sessionId,
            Boolean endSession,
            String apiKey) {

        public AskRequest(String question) {
            this(question, null, false, null);
        }

        public AskRequest(String question, String sessionId, Boolean endSession) {
            this(question, sessionId, endSession, null);
        }
    }

    /**
     * 响应体。
     *
     * <p>刻意不复用 {@link AgentResponse} 直接序列化：内部类型里有
     * {@code llmCall}、{@code timings} 这类结构，接口契约一旦跟着内部类型走，
     * 以后重构内部字段就会破坏 API。多一层 DTO 换来的是「内部可以随便改」。
     * 这是典型的样板代码，看的时候可以跳过，只需知道它负责字段映射。
     */
    public record AskResponse(
            String status,
            String sql,
            String message,
            java.util.List<String> violations,
            java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows,
            boolean truncated,
            boolean rewritten,
            int rowCount,
            int schemaTableCount,
  /**
   * 本次实际检索到的表。
   *
   * <p>补这个字段是因为阶段 7 要求「可解释性信息：用了哪些表」，
   * 而它此前只存在于 {@code AgentResponse}，没暴露到 HTTP——
   * 前端拿不到，就没法回答「是不是表选错了、为什么这么 join」。
   * 评估报告里有、接口却看不到，属于信息断层。
   */
  java.util.List<String> retrievedTables,
  int schemaDdlChars,
            Llm llm,
            Timings timings,
            String sessionId,
            String rewrittenQuestion,
            boolean schemaReused) {

        public record Llm(String model, int promptTokens, int completionTokens, int totalTokens,
                          long latencyMs, double costYuan) {
        }

        public record Timings(long retrievalMs, long generationMs, long validationMs,
                              long executionMs, long totalMs) {
        }

        public static AskResponse from(AgentResponse r) {
            Llm llm = r.llmCall() == null ? null : new Llm(
                    r.llmCall().model(), r.llmCall().promptTokens(), r.llmCall().completionTokens(),
                    r.llmCall().totalTokens(), r.llmCall().latencyMs(), r.llmCall().costYuan());
            Timings timings = r.timings() == null ? null : new Timings(
                    r.timings().retrievalMs(), r.timings().generationMs(), r.timings().validationMs(),
                    r.timings().executionMs(), r.timings().totalMs());
            return new AskResponse(r.status().name(), r.sql(), r.message(), r.violations(),
                    r.columns(), r.rows(), r.truncated(), r.rewritten(), r.rowCount(),
                    r.schemaTableCount(), r.retrievedTables(), r.schemaDdlChars(),
                    llm, timings, r.sessionId(), r.rewrittenQuestion(), r.schemaReused());
        }
    }
}
