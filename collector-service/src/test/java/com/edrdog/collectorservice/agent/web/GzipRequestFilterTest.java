package com.edrdog.collectorservice.agent.web;

import com.edrdog.collectorservice.agent.AgentService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 청크 전송(Content-Length 없음)은 MockMvc 로 만들 수 없어 필터를 직접 부른다. */
class GzipRequestFilterTest {

    private final GzipRequestFilter filter = new GzipRequestFilter(Mockito.mock(AgentService.class));

    /** Content-Length 를 모르는 요청. 청크 전송과 같다. */
    private static MockHttpServletRequest chunked(int bytes) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agent/events") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        req.setContent(new byte[bytes]);
        return req;
    }

    /** 체인이 본문을 끝까지 읽고 읽은 바이트 수를 돌려준다. */
    private long drain(MockHttpServletRequest req) throws Exception {
        long[] total = {0};
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            try (InputStream in = r.getInputStream()) {
                total[0] = in.readAllBytes().length;
            }
        });
        return total[0];
    }

    @Test
    void 청크_본문이_상한을_넘으면_읽는_도중_413_이다() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> drain(chunked((int) GzipRequestFilter.MAX_DECOMPRESSED_BYTES + 1)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
    }

    @Test
    void 청크_본문이_상한_이하면_끝까지_읽힌다() throws Exception {
        int size = (int) GzipRequestFilter.MAX_DECOMPRESSED_BYTES;
        assertEquals(size, drain(chunked(size)));
    }
}
