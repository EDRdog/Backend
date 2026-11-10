package com.edrdog.apiservice.access;

import com.edrdog.apiservice.security.ApiKeyPolicy;
import com.edrdog.apiservice.security.SwaggerAuthPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 식별·인증 없이 URL 로 직접 접근(KISA EDR-BF-20.1). api-service 의 모든 매핑을 훑어 인증 없이 열린 경로를 목록으로 만들고
 * 의도한 것만 남았는지 본다. 새 매핑이 열린 채 추가되면 이 테스트가 실패한다.
 *
 * <p>인증이 있는 것으로 치는 경우: 세션 Bearer({@link RequiresRole}), 서비스 간 키({@code /api/internal/}),
 * Swagger Basic, 프론트 X-API-Key(예외 목록에 없는 경로).
 */
class UrlAccessTest extends AccessTestBase {

    /** 의도해서 열어 둔 매핑. 늘리려면 왜 열어야 하는지 ADR 033 에 적고 여기에 올린다. */
    private static final List<String> INTENTIONALLY_OPEN = List.of(
            "POST /api/auth/signup",                     // 가입
            "POST /api/auth/login",                      // 로그인(안에서 허용 IP 를 본다)
            "POST /api/auth/logout",                     // 자기 토큰만 지운다. 멱등이라 무효 토큰도 204
            "POST /api/demo/collect/{scenario}",         // 발표 시연. 데모 조직과 시나리오 4종으로 좁혀 있다(DemoController)
            "GET /i/{token}",                            // 설치 스크립트. 링크의 설치 토큰이 인증이다
            "GET /i/{token}.ps1");                       // 위와 같다(Windows)

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private record Mapping(String method, String path, HandlerMethod handler) {
        String label() {
            return method + " " + path;
        }

        /** 경로 변수를 아무 값으로 채운다. */
        String concretePath() {
            return path.replaceAll("\\{[^}]+}", "x");
        }
    }

    private List<Mapping> allMappings() {
        List<Mapping> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var methods = e.getKey().getMethodsCondition().getMethods();
            for (String path : e.getKey().getPathPatternsCondition().getPatternValues()) {
                if (methods.isEmpty()) {
                    out.add(new Mapping("ALL", path, e.getValue()));
                }
                methods.forEach(m -> out.add(new Mapping(m.name(), path, e.getValue())));
            }
        }
        return out;
    }

    private static boolean hasRole(HandlerMethod h) {
        return AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), RequiresRole.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(h.getBeanType(), RequiresRole.class) != null;
    }

    private static boolean protectedByOtherMeans(Mapping m) {
        return m.path().startsWith("/api/internal/")
                || new SwaggerAuthPolicy("u", "p").isProtected(m.path())
                || !new ApiKeyPolicy("k").isExempt(m.path());
    }

    @Test
    void openMappingsAreExactlyTheIntentionalOnes() {
        TreeSet<String> open = new TreeSet<>();
        for (Mapping m : allMappings()) {
            if (!hasRole(m.handler()) && !protectedByOtherMeans(m)) {
                open.add(m.label());
            }
        }

        assertEquals(new TreeSet<>(INTENTIONALLY_OPEN), open);
    }

    @Test
    void everySessionMappingRejectsRequestsWithoutAValidToken() throws Exception {
        List<Mapping> session = allMappings().stream().filter(m -> hasRole(m.handler())).toList();
        assertTrue(session.size() > 40, "세션 인증 매핑을 찾지 못했다: " + session.size());

        for (Mapping m : session) {
            // 요청 본문이 없거나 필수 값이 없어도 핸들러 앞에서 401 이 나야 한다
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(m.method()), m.concretePath())
                            .header("X-API-Key", "dev-api-key").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(m.method()), m.concretePath())
                            .header("X-API-Key", "dev-api-key").header("Authorization", "Bearer not-a-session"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void healthIsTheOnlyOpenActuatorEndpointAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("components"))));
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerNeedsItsOwnLogin() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownPathsAreNotOpenEither() throws Exception {
        assertFalse(allMappings().isEmpty());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }
}
