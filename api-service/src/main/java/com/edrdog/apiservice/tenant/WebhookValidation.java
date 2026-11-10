package com.edrdog.apiservice.tenant;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Slack webhook URL 검증(순수). 해커톤 수준의 최소 규칙만 둔다.
 */
public final class WebhookValidation {

    private WebhookValidation() {
    }

    private static final int MAX_LENGTH = 255;   // tenants/users 의 webhook 칸. 넘기면 저장이 500 이 된다

    /** https 이고 호스트가 있는 URL 만. 공백, 제어 문자가 있어 URI 로 못 읽으면 무효. */
    public static boolean valid(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_LENGTH || !url.startsWith("https://")) {
            return false;
        }
        try {
            return new URI(url).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
