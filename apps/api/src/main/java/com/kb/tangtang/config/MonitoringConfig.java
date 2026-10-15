package com.kb.tangtang.config;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.logging.Log4j2Metrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

@Configuration
public class MonitoringConfig implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MonitoringConfig.class);

    @Value("${monitoring.metrics.port:9404}")
    private int metricsPort;

    private HttpServer metricsServer;
    private JvmGcMetrics jvmGcMetrics;

    @Bean
    public PrometheusMeterRegistry meterRegistry() throws IOException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        registry.config().commonTags("application", "tangtang");

        new JvmMemoryMetrics().bindTo(registry);
        jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(registry);

        new JvmThreadMetrics().bindTo(registry);      // 살아 있는 스레드, 상태별 스레드
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);      // 프로세스 · 시스템 CPU 사용률
        new UptimeMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
        new Log4j2Metrics().bindTo(registry);         // 레벨별 로그 개수 (ERROR 급증 감지용)

        startMetricsServer(registry);
        return registry;
    }

    /**
     * /metrics 를 8080 이 아닌 별도 포트로 연다.
     * 8080 은 인터넷에 열려 있어 지표(URI 목록, 에러 수, JVM 상태)가 그대로 노출된다.
     * 이 포트는 공개하지 않는다. 로컬은 127.0.0.1 에만, 운영은 보안그룹으로 모니터링 서버만 허용한다.
     */
    private void startMetricsServer(PrometheusMeterRegistry registry) throws IOException {
        metricsServer = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        metricsServer.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        metricsServer.start();
        log.info("메트릭 엔드포인트 시작 port={}", metricsPort);
    }

    @Override
    public void destroy() {
        if (metricsServer != null) {
            metricsServer.stop(0);
        }
        if (jvmGcMetrics != null) {
            jvmGcMetrics.close();
        }
    }
}
