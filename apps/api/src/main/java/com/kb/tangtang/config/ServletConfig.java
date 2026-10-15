package com.kb.tangtang.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.common.auth.JwtAuthInterceptor;
import com.kb.tangtang.common.auth.LoginUserArgumentResolver;
import com.kb.tangtang.common.docs.SwaggerAccessInterceptor;
import com.kb.tangtang.common.monitoring.HttpMetricsInterceptor;
import com.kb.tangtang.common.storage.ImageStorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 서블릿(웹) 컨텍스트 설정.
 * 이 프로젝트는 Vue3 SPA + REST API 구조다. JSP / 뷰 리졸버는 사용하지 않는다.
 * 응답은 전부 JSON 이며 공통 래퍼(ApiResponse)를 사용한다.
 */
@EnableWebMvc
@ComponentScan(
        basePackages = "com.kb.tangtang",
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = {Controller.class, ControllerAdvice.class}))
public class ServletConfig implements WebMvcConfigurer {

    /**
     * SwaggerAccessInterceptor 를 걸 경로. springfox 가 문서를 그리는 데 필요한 전부다 —
     * 하나라도 빠지면 인증을 통과한 뒤에도 화면이 비거나 "Failed to load API definition" 이 뜬다.
     *
     * <p><b>모든 항목에 {@code /**} 짝을 둔 이유.</b> 인터셉터 패턴이 <b>정확 매칭</b>이면
     * 핸들러는 받아주는데 인터셉터는 안 걸리는 URL 이 생겨 <b>인증이 통째로 우회된다.</b>
     * 실제로 {@code /v2/api-docs/} (슬래시 하나)가 401 이 아니라 200 으로 명세 전문을 뱉었다.
     * {@code JwtAuthInterceptor} 가 무사한 건 {@code /api/**} 가 이미 {@code **} 라서다.
     * {@code SwaggerAccessPathTest} 가 이 성질을 고정한다.
     */
    static final String[] SWAGGER_PATH_PATTERNS = {
            "/swagger-ui.html", "/swagger-ui.html/**",
            "/v2/api-docs", "/v2/api-docs/**",
            "/swagger-resources", "/swagger-resources/**",
            "/configuration", "/configuration/**",
            "/webjars", "/webjars/**"};

    /*
     * 두 컨텍스트 구조 주의:
     *   RootConfig  가 @Controller·@ControllerAdvice 를 제외한 모든 @Component 를 스캔한다.
     *   → JwtAuthInterceptor · LoginUserArgumentResolver 는 루트 컨텍스트의 빈이다.
     *   ServletConfig(자식)는 부모 컨텍스트의 빈을 주입받을 수 있으므로 아래가 동작한다.
     */
    @Autowired
    private JwtAuthInterceptor jwtAuthInterceptor;

    @Autowired
    private LoginUserArgumentResolver loginUserArgumentResolver;

    /**
     * 배포 환경에서 Swagger 문서를 Basic 인증으로 가린다. 로컬은 그대로 통과한다.
     */
    @Autowired
    private SwaggerAccessInterceptor swaggerAccessInterceptor;

    /*
     * 서블릿 컨텍스트에는 PropertySourcesPlaceholderConfigurer 가 없어 @Value 가 풀리지 않는다.
     * 값을 루트 컨텍스트의 빈에서 받아온다 — 위 두 빈과 같은 경로다.
     */
    @Autowired
    private ImageStorageProperties imageStorageProperties;

    /**
     * RootConfig 의 ObjectMapper 빈(JavaTimeModule 등록됨) — JwtAuthInterceptor/GoogleOAuthClient 와 동일 설정을 공유한다.
     */
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HttpMetricsInterceptor httpMetricsInterceptor;

    /*
     * @EnableWebMvc 의 기본 MappingJackson2HttpMessageConverter 는 Jackson2ObjectMapperBuilder 가
     * classpath 에서 JavaTimeModule 을 찾아 자동 등록하지만, WRITE_DATES_AS_TIMESTAMPS 는 기본값(true)
     * 그대로라 LocalDateTime 이 ISO-8601 문자열이 아니라 [2027,8,4,...] 숫자 배열로 내려간다
     * (실측, ConsentControllerTest 참고). RootConfig 의 objectMapper 빈으로 교체해 통일한다.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter) {
                ((MappingJackson2HttpMessageConverter) converter).setObjectMapper(objectMapper);
            }
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(httpMetricsInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/notifications/stream");


        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                // 로그인 자체 경로와 헬스체크는 인증 없이 열어둔다
                .excludePathPatterns("/api/health", "/api/auth/**");

        /*
         * Swagger 문서는 /api/** 밖이라 위 인터셉터가 닿지 않는다. 배포 서버가 공인 IP 로
         * 떠 있어 그대로 두면 API 구조 전체가 무인증으로 공개된다 (2026-08-14 실측 200).
         * 아래 경로는 springfox 가 문서를 그리는 데 필요한 전부다 — 하나라도 빠지면
         * 인증을 통과한 뒤에도 화면이 비거나 "Failed to load API definition" 이 뜬다.
         */
        registry.addInterceptor(swaggerAccessInterceptor)
                .addPathPatterns(SWAGGER_PATH_PATTERNS);
    }

    /**
     * 끝 슬래시를 핸들러가 관대하게 받아주던 동작을 끈다.
     *
     * <p>기본값(true)에서는 {@code /v2/api-docs/} 가 핸들러에는 매칭되는데 인터셉터 패턴
     * {@code /v2/api-docs} 에는 매칭되지 않아 <b>인증만 빠진 채 본문이 나갔다.</b>
     * 끄면 같은 URL 이 404 가 되어 애초에 핸들러까지 닿지 않는다. 위 패턴 확장이 벨트라면
     * 이쪽이 근본 수정이다.
     *
     * <p>Spring 6.0 은 이 값을 deprecated 로 두고 <b>기본값을 false 로 바꿨다</b> — 즉 상위 버전의
     * 기본 동작으로 맞추는 것이다. 프론트({@code apps/web/src})와 백엔드 {@code @*Mapping} 을
     * 전수 확인했고 끝 슬래시에 의존하는 경로는 <b>하나도 없다</b>(2026-08-19 실측).
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseTrailingSlashMatch(false);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(loginUserArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 로컬 개발은 Vite 프록시(same-origin)라 CORS 를 타지 않지만,
                // 프록시를 끄고 직접 붙이는 경우를 위해 남겨둔다.
                .allowedOrigins("https://monorepo-three-ruby-81.vercel.app", "http://localhost:5173")
                // PATCH 가 빠져 있으면 배포 환경(Vercel↔EC2, 교차 출처)에서만 프리플라이트가 막힌다.
                // 로컬은 Vite 프록시라 same-origin 이어서 끝까지 드러나지 않는다. (2026-08-11 추가)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 운영 배포 시 Vue 빌드 산출물을 webapp/resources 아래에 두고 서빙하기 위한 설정
        registry.addResourceHandler("/resources/**")
                .addResourceLocations("/resources/");

        /*
         * 업로드된 프로필 이미지. 인터셉터는 /api/** 에만 걸려 있으므로 이 경로는 인증 없이 열린다 —
         * 그룹 멤버에게 보이는 값이라 의도된 결과이며, 키에 UUID 가 들어 있어 추측으로 열 수 없다.
         * 경로 끝의 '/' 가 없으면 매핑이 조용히 실패한다.
         */
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + imageStorageProperties.getLocalDir() + "/");

        /*
         * Swagger UI. springfox-swagger-ui 2.9.2 는 정적 파일을 jar 안
         * META-INF/resources 아래에 담아둔다 — 이 두 줄이 없으면 /swagger-ui.html 이 404 다.
         * (/v2/api-docs 와 /swagger-resources 는 springfox 가 컨트롤러로 직접 매핑하므로
         *  리소스 핸들러가 필요 없다)
         */
        registry.addResourceHandler("/swagger-ui.html")
                .addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}
