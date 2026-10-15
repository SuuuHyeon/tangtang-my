package com.kb.tangtang.common.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;

@Component
public class HttpMetricsInterceptor implements HandlerInterceptor {

    private static final String SAMPLE_ATTRIBUTE = HttpMetricsInterceptor.class.getName() + ".SAMPLE";

    private final MeterRegistry meterRegistry;

    public HttpMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(SAMPLE_ATTRIBUTE, Timer.start(meterRegistry));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object sample = request.getAttribute(SAMPLE_ATTRIBUTE);
        if (!(sample instanceof Timer.Sample)) {
            return;
        }
        int status = response.getStatus();
        if (ex != null && status < 400) {
            status = 500;   // 처리되지 않은 예외는 500 으로 센다
        }
        Timer timer = Timer.builder("http.server.requests")
                .description("HTTP 요청 처리 시간")
                .tag("method", request.getMethod())
                .tag("uri", uriPattern(request))
                .tag("status", String.valueOf(status))
                .tag("outcome", outcome(status))
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry);
        ((Timer.Sample) sample).stop(timer);
    }

    /**
     * /api/group-challenges/42 가 아니라 /api/group-challenges/{groupId} 로 기록한다
     */
    private String uriPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : "UNMAPPED";
    }

    private String outcome(int status) {
        if (status >= 500) {
            return "SERVER_ERROR";
        }
        if (status >= 400) {
            return "CLIENT_ERROR";
        }
        return "SUCCESS";
    }
}
