package com.edrdog.collectorservice.agent;

import com.edrdog.collectorservice.agent.domain.AgentNode;
import com.edrdog.collectorservice.agent.repository.AgentNodeRepository;
import com.edrdog.collectorservice.responder.ResponderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 (tenant, host) 행이 둘이 되면 enroll 이 영구히 막히던 문제(#194)를 막는 두 장치를 검증한다.
 * 유니크 제약은 중복 생성을 막고, 리스트 조회는 제약이 붙기 전에 생긴 중복을 스스로 치운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
// 순서를 고정한다. 중복 테스트가 제약을 떨어뜨렸다 손으로 되돌리므로, 그 뒤에 제약을 검증하면
// 매핑이 아니라 테스트가 만든 제약을 보게 된다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentEnrollDuplicateTest {

    private static final String SECRET = "tenant-9-secret";
    private static final long TENANT = 9L;
    private static final String HOST = "gimdonghyeon-ui-MacBookPro.local";
    private static final String CONSTRAINT = "uk_agent_nodes_tenant_host";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AgentNodeRepository nodes;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EventsProducer producer;

    @MockitoBean
    private TenantResolverClient tenants;

    @MockitoBean
    private ResponderClient responder;

    @AfterEach
    void 데이터를_되돌린다() {
        nodes.deleteAll(nodes.findByTenantId(TENANT));
        nodes.flush();
    }

    @Test
    @Order(1)
    void 같은_tenant_host_행은_둘이_될_수_없다() {
        nodes.saveAndFlush(AgentNode.enroll("hash-a", TENANT, HOST, "darwin", Instant.parse("2026-08-02T09:30:09Z")));

        assertThrows(DataIntegrityViolationException.class, () -> nodes.saveAndFlush(
                AgentNode.enroll("hash-b", TENANT, HOST, "darwin", Instant.parse("2026-08-02T09:30:10Z"))));
    }

    /**
     * 제약이 붙기 전에 생긴 중복이 남아 있어도 enroll 이 터지지 않고 행을 하나로 정리해야 한다.
     * 조회가 터지면 재-enroll 로 정리될 기회조차 없어 그 호스트는 사람이 DB 를 고칠 때까지 등록을 못 한다.
     */
    @Test
    @Order(2)
    void 이미_중복이_있어도_enroll_이_정리하고_등록한다() throws Exception {
        Instant first = Instant.parse("2026-08-02T09:30:09Z");
        // 제약이 있으면 이 상황 자체를 만들 수 없다. 제약이 붙기 전에 생긴 데이터를 재현한다.
        jdbc.execute("ALTER TABLE agent_nodes DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
        nodes.saveAndFlush(AgentNode.enroll("hash-old", TENANT, HOST, "darwin", first));
        nodes.saveAndFlush(AgentNode.enroll("hash-new", TENANT, HOST, "darwin", Instant.parse("2026-08-02T09:31:09Z")));

        when(tenants.resolve(SECRET)).thenReturn(Optional.of(TENANT));
        mvc.perform(post("/api/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enroll_secret\":\"" + SECRET + "\",\"host_identifier\":\"" + HOST + "\","
                                + "\"platform\":\"darwin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node_key").isNotEmpty());

        List<AgentNode> left = nodes.findByTenantId(TENANT);
        assertEquals(1, left.size());
        assertTrue(nodes.findById("hash-old").isEmpty());
        assertTrue(nodes.findById("hash-new").isEmpty());
        // 최초 등록 시각은 가장 오래된 행에서 물려받는다
        assertEquals(first, left.get(0).getEnrolledAt());

        // 컨텍스트를 공유하는 다른 테스트에 제약이 없는 스키마를 넘기지 않는다
        jdbc.execute("ALTER TABLE agent_nodes ADD CONSTRAINT IF NOT EXISTS " + CONSTRAINT
                + " UNIQUE (tenant_id, host_identifier)");
    }
}
