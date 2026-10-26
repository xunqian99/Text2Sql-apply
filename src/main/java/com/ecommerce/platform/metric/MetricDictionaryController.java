package com.ecommerce.platform.metric;

import com.ecommerce.platform.common.ApiResponse;
import com.text2sql.agent.semantic.Metric;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * 业务指标字典 RESTful 接口。
 * 暴露当前数仓中注册的全部标准口径（GMV、客单价、复购率等），供前端中台管理展示。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricDictionaryController {

    private final MetricRegistry metricRegistry;

    public MetricDictionaryController(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @GetMapping
    public ApiResponse<Collection<Metric>> listMetrics() {
        return ApiResponse.ok(metricRegistry.all());
    }
}
