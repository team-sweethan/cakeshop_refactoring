package com.cakeshop.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final boolean publicPreview;
    private final boolean metricsPublic;

    public SecurityConfig(
            @Value("${app.mockup.public-preview:false}") boolean publicPreview,
            @Value("${app.monitoring.metrics-public:false}") boolean metricsPublic) {
        this.publicPreview = publicPreview;
        this.metricsPublic = metricsPublic;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            SessionRegistry sessionRegistry,
            ObjectProvider<OAuth2LoginSuccessHandler> oauth2LoginSuccessHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository) throws Exception {
        RequestMatcher passwordRecoveryRequest =
                SecurityConfig::isPasswordRecoveryRequest;
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return !"/cart/count".equals(path) && !path.startsWith("/api/") && !"/ws".equals(path) && !path.startsWith("/ws/");
        });
        LoginUrlAuthenticationEntryPoint customerLoginEntryPoint =
                new LoginUrlAuthenticationEntryPoint("/login");
        LoginUrlAuthenticationEntryPoint adminLoginEntryPoint =
                new LoginUrlAuthenticationEntryPoint("/admin/login");
        AuthenticationEntryPoint portalLoginEntryPoint = (request, response, exception) -> {
            String path = request.getRequestURI()
                    .substring(request.getContextPath().length());
            if (path.startsWith("/api/chat/") || path.startsWith("/api/admin/chat/")) {
                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
                return;
            }
            if ("/admin".equals(path) || path.startsWith("/admin/")) {
                adminLoginEntryPoint.commence(request, response, exception);
                return;
            }
            customerLoginEntryPoint.commence(request, response, exception);
        };
        AccessDeniedHandler defaultAccessDeniedHandler = new AccessDeniedHandlerImpl();
        RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
        AccessDeniedHandler portalAccessDeniedHandler = (request, response, exception) -> {
            boolean admin = request.getUserPrincipal() instanceof Authentication auth
                    && auth.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            if (admin && HttpMethod.GET.matches(request.getMethod())) {
                redirectStrategy.sendRedirect(request, response, "/admin");
                return;
            }
            defaultAccessDeniedHandler.handle(request, response, exception);
        };

        http
            // 웹훅 경로 및 웹소켓 SockJS 폴백 경로만 CSRF 제외 — 전체 비활성화 금지
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/toss", "/webhooks/donworry", "/ws/**"))
            .requestCache(cache -> cache.requestCache(requestCache))
            .authorizeHttpRequests(auth -> {
                // ① 공개 GET을 먼저 선언 (matcher 순서 = 우선순위)
                // 이메일 인증 기반 비밀번호 재설정 경로는 비로그인 사용자에게 공개한다.
                auth.requestMatchers(passwordRecoveryRequest).permitAll();
                auth.requestMatchers(
                        "/", "/login", "/signup", "/join", "/emailCheck", "/find-email",
                        "/find-email/login", "/email-verifications/signup/**",
                        "/email-verifications/password-reset/**",
                        "/oauth/signup", "/oauth2/**", "/login/oauth2/**",
                        "/api/notifications/unread-count", "/api/notifications/test-sms",
                        "/products/**", "/screens", "/favicon.ico",
                        "/css/**", "/js/**", "/webjars/**", "/images/**", "/uploads/**", "/error")
                        .permitAll();
                auth.requestMatchers("/admin/login").permitAll();
                // 로드밸런서/헬스체크가 인증 없이 호출할 수 있도록 허용
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();

                if (metricsPublic) {
                    // 로컬 측정에서만 연다. Prometheus 수집기는 로그인하지 않으므로 열지 않으면
                    // anyRequest().hasRole("USER")에 걸려 403이 된다.
                    // 지표에는 모든 엔드포인트의 URI 패턴과 커넥션 풀·JVM 내부 상태가 들어 있어
                    // 배포 환경에서는 닫아 둔다 (application.yml 의 app.monitoring.metrics-public).
                    auth.requestMatchers("/actuator/prometheus").permitAll();
                }
                // 공지는 비로그인도 읽어야 하는 안내다. /community/{id:\d+}가 숫자만 받으므로
                // "notices"는 그 규칙에 걸리지 않아 여기에 따로 적어야 한다.
                auth.requestMatchers(HttpMethod.GET,
                                "/community", "/community/{id:\\d+}",
                                "/community/{postId:\\d+}/comments/{commentId:\\d+}",
                                "/community/notices", "/community/notices/{id:\\d+}")
                        .permitAll();
                auth.requestMatchers(HttpMethod.POST, "/webhooks/toss", "/webhooks/donworry").permitAll();
                // 상품 상세에서 시작하는 주문·문의 흐름은 local 공개 미리보기에서도 회원 로그인이 필요하다.
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/orders/checkout",
                        "/orders/checkout/**",
                        "/orders/custom/options",
                        "/chat"
                ).hasRole("USER");

                if (publicPreview) {
                    // local 프로필에서만 고객 목업 흐름을 로그인 없이 확인한다.
                    // 관리자 화면은 preview에서도 열지 않는다 — 아래 /admin/** 규칙에 따라 관리자 로그인이 필요하다.
                    // /community/new는 목업이 아니게 되면서 뺐다. 저장 경로가 생긴 화면을 비로그인에게
                    // 열어 두면 폼을 다 채우고 등록에서야 로그인으로 튕긴다.
                    // /reviews/**도 조각 1에서 같은 이유로 뺐다 — 작성 폼이 주문 소유권을 검증하므로
                    // 익명 사용자는 폼을 다 채운 뒤에야 튕긴다.
                    auth.requestMatchers(
                            HttpMethod.GET,
                            "/orders/**", "/notifications", "/api/notifications", "/api/notifications/**")
                            .permitAll();
                }

                // ② 관리자. 모든 관리자 화면 및 관리자 API는 관리자 로그인을 요구한다.
                auth.requestMatchers("/admin", "/admin/**", "/api/admin/**").hasRole("ADMIN");
                // 고객과 관리자가 각자 받은 채팅/알림을 같은 API에서 조회하고 읽음 처리한다.
                auth.requestMatchers("/ws", "/ws/**", "/api/chat", "/api/chat/**", "/api/notifications", "/api/notifications/**")
                        .hasAnyRole("USER", "ADMIN");
                // 커뮤니티 댓글은 관리자도 단다 — 질문 분류에 답할 사람이 가게 쪽에 없어서다.
                // 여는 것은 댓글 작성·자기 댓글 삭제 둘뿐이다. 좋아요는 인기 점수(좋아요=25점)를
                // 운영자가 움직이는 경로가 되고, 신고는 차단 권한자가 자기에게 보고하는 일이라 열지 않는다
                // (docs/community/specs/community-comment.md).
                auth.requestMatchers(HttpMethod.POST,
                                "/community/{postId:\\d+}/comments",
                                "/community/{postId:\\d+}/comments/{commentId:\\d+}/delete")
                        .hasAnyRole("USER", "ADMIN");
                // ③ 나머지 회원 전용 기능은 일반 회원만 사용한다.
                auth.anyRequest().hasRole("USER");
            })
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(portalLoginEntryPoint)
                .accessDeniedHandler(portalAccessDeniedHandler))
            .formLogin(form -> form
                .loginPage("/login")
                // 화면과 도메인 모두 이메일을 로그인 식별자로 사용한다.
                .usernameParameter("email")
                .failureHandler(new PortalAuthenticationFailureHandler())
                // 역할별 기본 진입점 분기: 관리자 → /admin, 고객 → / (저장된 요청이 있으면 그 경로 우선)
                .successHandler(new RoleBasedAuthenticationSuccessHandler())
                .permitAll()
            )
            .sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry)
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(new PortalLogoutSuccessHandler())
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );
        OAuth2LoginSuccessHandler oauthSuccessHandler = oauth2LoginSuccessHandler.getIfAvailable();
        if (oauthSuccessHandler != null) {
            ClientRegistrationRepository registrations = clientRegistrationRepository.getIfAvailable();
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .authorizationEndpoint(endpoint -> {
                        if (registrations != null) {
                            endpoint.authorizationRequestResolver(
                                    new OAuth2AccountSelectionRequestResolver(registrations));
                        }
                    })
                    .successHandler(oauthSuccessHandler)
                    .failureHandler(new SimpleUrlAuthenticationFailureHandler("/login?oauthError")));
        }
        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static boolean isPasswordRecoveryRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/find-password".equals(path)
                || "/reset-password".equals(path);
    }
}
