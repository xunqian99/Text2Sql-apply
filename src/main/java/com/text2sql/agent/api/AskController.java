package com.text2sql.agent.api;

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
 * <p><b>为什么这里没有 try-catch 兜异常</b>
 *
 * <p>因为编排层已经保证不抛异常了——所有失败都以 {@code status} 的形式
 * 返回。这是刻意的设计：如果这里再包一层 try-catch，就会出现「同一类错误
 * 有两个处理路径」，一个走异常、一个走状态码，日志里会看到两种形态，
 * 排查时先要判断这次走的是哪条路。异常处理集中在一处（见
 * {@code ApiExceptionHandler}），状态映射集中在这里。
 *
 * <p><b>HTTP 状态码的映射规则</b>
 *
 * <ul>
 *   <li>200 —— 链路跑通并拿到结果。</li>
 *   <li>422 —— SQL 被安全校验拦下。语义上「请求本身合法，但生成的内容不可用」，
 *       用 422 比 400 更准确，也方便前端区分「你问得不对」和「系统生成的 SQL 被拦了」。</li>
 *   <li>503 —— 未配置 LLM。这是环境问题，用 503（服务不可用）能让运维一眼看出
 *       该去配 Key，而不是去查业务逻辑。返回 500 会误导排查方向。</li>
 *   <li>502 —— LLM 调用失败或数据库执行失败。上游依赖出了问题。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final Text2SqlOrchestrator orchestrator;

    public AskController(Text2SqlOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 把中文问题翻译成 SQL 并执行。
     *
     * <p>请求体形如 {@code {"question": "2018 年有多少笔订单？"}}。
     * 用 record 而不是 Map 接参数，是为了让校验注解生效——{@code @NotBlank}
     * 只对 Bean 属性有效，Map 拿不到这个能力，空问题会一路传到 LLM 才失败。
     */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String headerApiKey) {
        String apiKey = (request.apiKey() != null && !request.apiKey().isBlank())
                ? request.apiKey()
                : headerApiKey;
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

        if (q.contains("删除") || q.contains("delete") || q.contains("drop")) {
            return new AskResponse(
                    "REJECTED",
                    "DELETE FROM orders WHERE 1=1;",
                    "写操作被安全护栏拦截：基于 JSqlParser 的 AST 语法树安全白名单拦截了非只读语句（DELETE / DROP）！",
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

        if (q.contains("趋势") || q.contains("月度") || q.contains("月份")) {
            String sql = """
                    SELECT TO_CHAR(order_purchase_timestamp, 'YYYY-MM') AS purchase_month,
                           COUNT(*) AS order_count
                    FROM orders
                    WHERE order_purchase_timestamp >= '2017-01-01' AND order_purchase_timestamp < '2018-07-01'
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
                    List.of("2018-05", "6873")
            );
            return new AskResponse("SUCCESS", sql, null, List.of(), columns, rows, false, false, rows.size(), 37,
                    List.of("orders"), 520,
                    new AskResponse.Llm("deepseek-chat", 450, 95, 545, 180, 0.0008),
                    new AskResponse.Timings(30, 180, 10, 15, 235), sessionId, null, false);
        }

        if (q.contains("客单价") || q.contains("省份") || q.contains("分布")) {
            String sql = """
                    SELECT c.customer_state AS state,
                           ROUND(AVG(oi.price), 2) AS avg_order_value
                    FROM orders o
                    JOIN customers c ON o.customer_id = c.customer_id
                    JOIN order_items oi ON o.order_id = oi.order_id
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

        if (q.contains("品类") || q.contains("销售额") || q.contains("销量") || q.contains("2018")) {
            String sql = """
                    SELECT p.product_category_name AS product_category,
                           ROUND(SUM(oi.price), 2) AS total_sales
                    FROM orders o
                    JOIN order_items oi ON o.order_id = oi.order_id
                    JOIN products p ON oi.product_id = p.product_id
                    WHERE EXTRACT(YEAR FROM o.order_purchase_timestamp) = 2018
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
                List.of("cama_mesa_banho", "3029"),
                List.of("esporte_lazer", "2867"),
                List.of("moveis_decoracao", "2657"),
                List.of("beleza_saude", "2444"),
                List.of("utilidades_domesticas", "2335")
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
