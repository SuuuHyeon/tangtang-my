package com.kb.tangtang.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;

/**
 * 루트 컨텍스트 설정.
 * DataSource / MyBatis / 트랜잭션 등 웹 계층을 제외한 모든 빈을 담당한다.
 * Controller · ControllerAdvice 는 ServletConfig 가 담당하므로 여기서는 제외한다.
 *
 * 설정 값은 application.properties(공통) 위에 application-local.properties(개인)가 덮어쓴다.
 * 접속 계정은 반드시 application-local.properties 에 둔다. (커밋 금지)
 */
@Configuration
@EnableTransactionManagement
@EnableScheduling   // NotificationDlqRetryScheduler (NT_01_04)
@EnableAsync        // NotificationRequestedListener 의 @Async
/*
 * 환경별 설정은 한 번에 하나만 로드한다.
 *   로컬 : APP_ENV 없음 → 기본값 local → application-local.properties (git 제외, 개인 시크릿)
 *   도커 : docker-compose 가 APP_ENV=docker 주입 → application-docker.properties
 *
 * 과거에 두 파일을 동시에 나열했다가, 로컬에서도 docker 파일이 로드돼
 * jdbc.driver 가 ${JDBC_DRIVER} 로 덮이면서 컨텍스트 로딩이 실패했다.
 * (실측: @PropertySource 는 뒤에 선언한 파일이 앞을 덮어쓴다)
 *
 * @PropertySource 의 경로에는 플레이스홀더를 쓸 수 있고, 이 시점에는
 * 시스템 프로퍼티·환경변수가 이미 해석 가능하다.
 */
@PropertySource(
        value = {
                "classpath:/application.properties",
                "classpath:/application-${app.env:local}.properties"
        },
        ignoreResourceNotFound = true)
@ComponentScan(
        basePackages = "com.kb.tangtang",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = {Controller.class, ControllerAdvice.class}))
// 매퍼 인터페이스는 반드시 @Mapper 를 붙일 것 (일반 인터페이스가 매퍼로 등록되는 사고 방지)
@MapperScan(basePackages = "com.kb.tangtang", annotationClass = Mapper.class)
public class RootConfig {

    /**
     * ${...} 치환을 엄격하게 수행한다.
     * 이 빈이 없으면 값을 못 찾았을 때 예외 대신 "${jdbc.url}" 문자열이 그대로 주입되어
     * 원인을 알기 어려운 에러가 난다. 없으면 "Could not resolve placeholder 'jdbc.url'" 로 즉시 실패한다.
     */
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Value("${jdbc.driver}") String driver;
    @Value("${jdbc.url}") String url;
    @Value("${jdbc.username}") String username;
    @Value("${jdbc.password}") String password;

    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    MeterRegistry meterRegistry;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driver);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        /*
         * maximumPoolSize 를 지정하지 않아 HikariCP 기본값 10 을 쓴다. **재보고 유지하는 값이다.**
         *
         * 2026-08-20 배포 환경(EC2 2 vCPU)에서 양방향으로 측정했다.
         * 120 VU · 거래 5만건 · 실패 0% · 워밍업 2회 버림 · 관찰 도구 끔.
         *
         *   풀 10 : 295.5 req/s · users/me med 32.25ms · categories p95 192.21ms
         *   풀  5 : 303.7 req/s · users/me med 43.22ms · categories p95 151.36ms
         *
         * 처리량 차이는 +2.8% 로 판정 기준(20%)에 못 미친다. **커넥션풀은 병목이 아니다.**
         *
         * 다만 풀 5 에서 p95 3종이 나란히 21% 내려가고(categories·summary·spending-trend)
         * 중앙값은 34% 오른다. 대기 장소를 CPU 런큐에서 커넥션풀로 옮겼을 뿐 총량은 그대로다.
         * 대부분의 사용자가 겪는 것은 p95 가 아니라 중앙값이라 10 을 유지한다.
         *
         * **진짜 병목은 CPU 다.** 같은 조건의 별도 회차에서 관찰한 값이다(그 회차 응답시간은 버렸다).
         *   vmstat : 유휴 0%, 실행 대기 큐 10~22 (2 vCPU 에 6배 초과), io 대기 0
         *   top    : mysqld 88.2% · java 47.1% — API 와 DB 가 한 대에 있어 DB 가 더 먹는다
         * 풀을 어느 쪽으로 만져도 CPU 총량은 늘지 않는다. 올리려면 vCPU 증설이나 DB 분리다.
         *
         * ⚠ 2026-08-14 로컬 실험(풀 30, -1.8%)은 **무효다.** 풀 30 회차가 톰캣 재시작 직후
         * 콜드 스타트였고, 콜드 회차는 처리량이 23% 손해를 본다는 것을 나중에 실측했다.
         * 그 회차를 근거로 "병목은 CPU"라고 적어 두었던 종전 주석을 위 실측으로 교체한다.
         *
         * 근거 없이 값을 바꾸지 말 것. 바꿀 일이 생기면 위와 같은 조건으로 다시 측정하고 정한다.
         */

        config.setPoolName("tangtang-hikari");
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setConfigLocation(applicationContext.getResource("classpath:/mybatis-config.xml"));
        factory.setDataSource(dataSource());
        // 매퍼 XML 위치: src/main/resources/mapper/<모듈>/*.xml
        factory.setMapperLocations(applicationContext.getResources("classpath:/mapper/**/*.xml"));
        return factory.getObject();
    }

    @Bean
    public DataSourceTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource());
    }

    /**
     * 프로그래밍 방식 트랜잭션 경계.
     *
     * ⚠ @Transactional 은 **프록시를 통해 들어온 호출에만** 걸린다. 한 클래스 안에서 자기 메서드를
     *   부르는 구조(FinancialSyncService: 외부 API 수집은 트랜잭션 밖, 저장만 트랜잭션 안)에서는
     *   애너테이션이 조용히 무시되고 문장 단위 auto-commit 으로 떨어진다.
     *   그런 구간은 이 템플릿으로 경계를 명시한다.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** 외부 API 호출용. 지금은 구글 OAuth 만 쓴다. */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * @EnableScheduling 전용 스케줄러.
     *
     * ⚠ 이 빈이 없으면 스프링이 **단일 스레드** 스케줄러로 폴백한다. SseHeartbeat.ping(15초)과
     *   NotificationDlqRetryScheduler.retryDue(60초)가 그 한 스레드를 나눠 쓰게 되고,
     *   응답 없는 클라이언트 하나가 SseEmitter.send() 에서 막히면 **모든 사용자의 하트비트**가 멈춘다.
     *   하트비트가 막으려던 바로 그 상황이다.
     *
     * poolSize 는 @Scheduled 개수보다 넉넉하게 잡는다. 자리가 모자라면 오래 걸리는 배치가 자리를
     * 점유하는 동안 하트비트가 밀린다. LLM 폴링과 금융 동기화 배치는 한 tick 에 여러 건의 외부 HTTP
     * 호출을 순차로 수행하므로 실제로 오래 점유한다(이슈 #199).
     *
     * 2026-08-18 기준 @Scheduled 20개 → poolSize 24 (여유 4).
     *   15초  SseHeartbeat.ping
     *   60초  NotificationDlqRetryScheduler.retryDue
     *   60초  LlmCategorizationScheduler.pollAndProcess
     *   60초  GroupVerdictScheduler.confirmDueVerdicts                 (#172)
     *   5분   GroupChallengeEvaluationScheduler.evaluateActiveGroups   (#168)
     *   5분   GroupTrialDeadlineScheduler.closeExpiredDefenses         (#170)
     *   20분  FinancialSyncBatchScheduler.runBatch
     *   일별  ChallengeGroupStatusScheduler.runDailyTransitions
     *   일별  RelativeMissionAssignmentScheduler.assignDailyMissions · recoverMissingDailyMissions
     *   일별  MissionNotificationScheduler.notifyMissionDeadline
     *   일별  FixedExpenseDetectionScheduler.runDailyFixedExpenseBatch
     *   일별  FixedExpensePaymentReminderScheduler.sendDuePaymentReminders
     *   월별  MonthlyReportBatchScheduler.generatePreviousMonthReports · recoverPreviousMonthReports
     *   월별  ChallengeMonthlyReportScheduler.finalizePreviousMonthReports
     *                                     · refreshPreviousMonthEndGroupRecords   (#256)
     *   월별  MissionNotificationScheduler.notifyPreviousMonthCertificates
     *   월별  MissionCertificateTitleScheduler.generatePreviousMonthTitles
     *                                        · recoverPreviousMonthTitles         (#273)
     *
     * ⚠ 스케줄러를 추가하면 이 목록과 poolSize 를 같이 갱신한다. 과거에 목록이 "4개" 로 멈춰 있어
     *   실제 개수를 세어 보기 전에는 여유가 있는지 판단할 수 없었다.
     *   2026-08-16 에도 같은 일이 반복됐다 — #256 이 refreshPreviousMonthEndGroupRecords 를
     *   추가하면서 목록을 갱신하지 않아 "13개 / poolSize 14" 로 적혀 있었지만 실제로는
     *   14개 / 14 (여유 0) 였다. #169 에서 세어 정정하고 18 로 올렸다.
     *   2026-08-18 (#172) 에 또 어긋나 있었다 — "15개 / 18 (여유 3)" 으로 적혀 있었지만
     *   실측은 19개 / 18 (여유 −1) 이었다. 목록에서 빠져 있던 것은 MissionNotificationScheduler(2) ·
     *   MissionCertificateTitleScheduler(2) 다. 세어 정정하고 #172 의 개표 1개를 더해 20개 / 24 로 둔다.
     *   #169 의 ACTIVE → JUDGING 전이는 새 @Scheduled 를 만들지 않고
     *   ChallengeGroupStatusScheduler.runDailyTransitions 안에 붙였다. #172 의 JUDGING → CLOSED
     *   최종 확정도 같은 방식으로 GroupVerdictScheduler.confirmDueVerdicts 안에 붙였다.
     *
     * 일별·월별 cron 은 자정 직후(00:01·00:10·00:15·00:30·00:40)와 21:00·23:00 에 몰린다.
     * 특히 고정지출 배치 둘은 같은 프로퍼티(${fixed.expense.detection.cron})를 써서 반드시 겹친다.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(24);
        scheduler.setThreadNamePrefix("tt-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /**
     * @EnableAsync 전용 실행기. 빈 이름이 반드시 taskExecutor 여야 @Async 가 집어 간다.
     *
     * ⚠ 이 빈이 없으면 SimpleAsyncTaskExecutor 로 폴백해 **이벤트 하나당 새 스레드**를 무제한 만든다.
     *   큐 용량을 두어 상한을 건다.
     */
    @Bean
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("tt-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 그룹 채팅 시스템 메시지 전용 실행기 ({@code ChatSystemMessageListener}, 이슈 #355).
     *
     * ⚠ <b>스레드가 하나인 것이 이 빈의 존재 이유다.</b> 기소 한 건이 적발(ViolationDetected)과
     *   개시(TrialOpened) 두 이벤트를 잇달아 쏘는데, 위 taskExecutor 는 corePoolSize 가 2 라
     *   두 태스크가 동시에 실행된다. 메시지 번호는 ChatMessageStore.append() 가 Redis INCR 로
     *   그 자리에서 발급하므로 **먼저 Redis 에 닿는 쪽이 앞 번호를 가져간다** —
     *   「변론이 시작됩니다」가 「한도를 넘었어요」보다 위에 뜨는 화면이 실제로 나온다.
     *   단일 스레드 + FIFO 큐라야 발행 순서가 곧 표시 순서가 된다.
     *   corePoolSize 를 올리지 말 것. 처리량을 늘려야 하면 순번 발급 방식부터 바꿔야 한다.
     *
     * ⚠ ChatMessageStore 의 APPEND_SCRIPT(2026-08-19)가 이 문제를 이미 막았다고 읽지 말 것.
     *   그 스크립트가 보장하는 것은 「받은 번호대로 List 에 들어간다」이지 **「어느 쪽이 먼저
     *   번호를 받는가」가 아니다.** 동시에 출발한 두 태스크의 도착 순서는 여전히 비결정적이다.
     *
     * 부수 효과로 채팅 Redis 왕복이 알림 처리와 스레드를 다투지 않는다.
     * 큐는 채팅 메시지가 밀렸을 때 상한을 두기 위한 것이다(taskExecutor 와 같은 취지).
     */
    @Bean
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("tt-chat-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * 기본 ObjectMapper 는 java.time(LocalDateTime 등)을 직렬화하지 못한다
     * (JavaTimeModule 이 없으면 InvalidDefinitionException 으로 500이 난다).
     * 이 빈은 JwtAuthInterceptor · GoogleOAuthClient 가 직접 주입받아 쓰므로,
     * MVC 응답 변환기(Jackson2ObjectMapperBuilder)와 동일하게 JavaTimeModule 을 등록하고
     * 타임스탬프(숫자 배열) 대신 ISO-8601 문자열로 쓰도록 맞춘다.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
