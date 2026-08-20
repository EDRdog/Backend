package com.edrdog.collectorservice.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Content-Encoding: gzip 인 요청 본문을 푼다.
 *
 * <p>Tomcat 은 요청 본문을 자동으로 풀지 않고 {@code server.compression} 은 응답 전용이다.
 * 이게 없으면 압축해서 보내는 에이전트가 400 을 맞는다.
 *
 * <p>헤더가 없으면 그대로 통과시킨다. 구버전 에이전트는 계속 비압축으로 보내므로 이 필터가
 * 먼저 배포돼도 아무 일도 일어나지 않는다. 그래서 서버를 먼저, 에이전트를 나중에 올린다.
 * 반대 순서면 서버가 못 푸는 요청이 먼저 들어온다.
 */
@Component
public class GzipRequestFilter extends OncePerRequestFilter {

    /**
     * 해제 크기 상한. 정상 배치는 500건(에이전트 batch_size 기본값)이라 1MB 언저리이고 그 8배다.
     * 상한 없이 열면 몇 KB 짜리 요청이 수 GB 로 풀려, 인증된 에이전트 하나로 collector 를 죽일 수 있다.
     */
    static final long MAX_DECOMPRESSED_BYTES = 8L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!"gzip".equalsIgnoreCase(req.getHeader(HttpHeaders.CONTENT_ENCODING))) {
            chain.doFilter(req, res);
            return;
        }
        chain.doFilter(new GzipRequestWrapper(req), res);
    }

    /** 본문만 푼 요청. 나머지는 원본에 그대로 위임한다. */
    private static final class GzipRequestWrapper extends HttpServletRequestWrapper {

        private final LimitedGzipStream body;
        private BufferedReader reader;

        GzipRequestWrapper(HttpServletRequest req) throws IOException {
            super(req);
            this.body = new LimitedGzipStream(req.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return body;
        }

        /** Jackson 은 InputStream 을 쓴다. 한쪽만 덮으면 Reader 로 읽는 코드가 압축 바이트를 그대로 본다. */
        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            }
            return reader;
        }

        // Content-Length 는 압축된 바이트라 풀린 본문 길이와 다르다. 그대로 두면 읽다가 잘린다.
        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }

    /** 풀린 바이트를 세면서 상한을 넘으면 끊는다. 다 풀고 나서 재면 이미 메모리를 쓴 뒤다. */
    private static final class LimitedGzipStream extends ServletInputStream {

        private final InputStream in;
        private long read;
        private boolean finished;

        LimitedGzipStream(InputStream compressed) throws IOException {
            this.in = new GZIPInputStream(compressed);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b < 0) {
                finished = true;
                return -1;
            }
            count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n < 0) {
                finished = true;
                return -1;
            }
            count(n);
            return n;
        }

        private void count(int n) {
            read += n;
            if (read > MAX_DECOMPRESSED_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "decompressed_body_too_large");
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("논블로킹 읽기는 쓰지 않는다");
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
