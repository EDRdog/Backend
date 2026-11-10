package com.edrdog.responderservice.api;

import com.edrdog.responderservice.command.CommandQueue;
import com.edrdog.responderservice.response.ExecuteResult;
import com.edrdog.responderservice.response.ResponseExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * responder 내부 API 인증(KISA H5). /api/responder/** 는 X-Internal-Key 가 맞을 때만 일을 하고,
 * 틀리면 컨트롤러에 닿기 전에 401 이다. 프로브 경로는 열려 있다.
 */
@WebMvcTest({KillController.class, CommandController.class})
@TestPropertySource(properties = "edrdog.internal.key=test-key")
class InternalKeyFilterTest {

    private static final String KILL_BODY = "{\"tenantId\":\"7\",\"host\":\"h\",\"target\":\"t\"}";
    private static final String RESULT_BODY =
            "{\"command_id\":\"c\",\"status\":\"KILLED\",\"tenant_id\":\"7\",\"host\":\"h\"}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ResponseExecutor executor;
    @MockitoBean
    private CommandQueue commands;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 키가_없으면_kill_은_401_이고_명령을_만들지_않는다() throws Exception {
        mvc.perform(json(post("/api/responder/kill"), KILL_BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(executor);
    }

    @Test
    void 키가_틀리면_kill_은_401() throws Exception {
        mvc.perform(json(post("/api/responder/kill"), KILL_BODY).header("X-Internal-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(executor);
    }

    @Test
    void 키가_없거나_틀리면_kill_결과_조회는_401() throws Exception {
        mvc.perform(get("/api/responder/kill/exec-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/responder/kill/exec-1").header("X-Internal-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(executor);
    }

    @Test
    void 키가_없거나_틀리면_대기_명령_조회는_401_이고_큐를_비우지_않는다() throws Exception {
        mvc.perform(get("/api/responder/commands").param("tenantId", "7").param("host", "h"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/responder/commands").param("tenantId", "7").param("host", "h")
                .header("X-Internal-Key", "wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(commands);
    }

    @Test
    void 키가_없거나_틀리면_명령_결과_보고는_401_이고_반영하지_않는다() throws Exception {
        mvc.perform(json(post("/api/responder/commands/result"), RESULT_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/responder/commands/result"), RESULT_BODY).header("X-Internal-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commands);
    }

    @Test
    void 키가_맞으면_통과한다() throws Exception {
        when(executor.killProcess(any(), any(), any()))
                .thenReturn(new ExecuteResult("7", "h", "t", ResponseExecutor.PENDING, "exec-1"));
        when(commands.drainFor("7", "h")).thenReturn(List.of());

        mvc.perform(json(post("/api/responder/kill"), KILL_BODY).header("X-Internal-Key", "test-key"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/responder/kill/exec-1").header("X-Internal-Key", "test-key"))
                .andExpect(status().isNotFound());   // 인증은 통과, 모르는 id
        mvc.perform(get("/api/responder/commands").param("tenantId", "7").param("host", "h")
                .header("X-Internal-Key", "test-key")).andExpect(status().isOk());
        mvc.perform(json(post("/api/responder/commands/result"), RESULT_BODY).header("X-Internal-Key", "test-key"))
                .andExpect(status().isOk());
    }

    @Test
    void 프로브_경로는_키_없이도_401_이_아니다() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());   // WebMvcTest 에는 actuator 가 없다. 필터가 막지 않는다는 것만 본다
    }
}
