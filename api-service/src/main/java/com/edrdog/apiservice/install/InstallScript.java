package com.edrdog.apiservice.install;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설치 스크립트 템플릿 치환(순수).
 *
 * <p>여기서 만든 문자열은 엔드포인트에서 {@code sudo bash} 로 실행된다. 값 검사를 빼면
 * 값에 섞인 따옴표나 {@code $} 가 스크립트 안에서 그대로 명령이 된다.
 */
public final class InstallScript {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    /** 값에 허용하는 문자. 공백과 셸 메타문자는 전부 빠져 있다. */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:/@=+~?&%-]+");

    /** SHA-256 지문. 콜론 없이 64자이거나 keytool 출력처럼 바이트마다 콜론이 있다. */
    private static final Pattern SHA256_FINGERPRINT =
            Pattern.compile("[A-Fa-f0-9]{64}|[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){31}");

    private InstallScript() {
    }

    /**
     * 설정된 서버 인증서 지문을 스크립트가 비교하는 꼴(콜론 없는 대문자 64자)로 바꾼다.
     *
     * @throws IllegalArgumentException SHA-256 지문 모양이 아닌 경우
     */
    public static String normalizeFingerprint(String value) {
        String v = value.strip();
        if (!SHA256_FINGERPRINT.matcher(v).matches()) {
            throw new IllegalArgumentException("서버 인증서 지문이 SHA-256 모양(16진수 64자, 콜론 허용)이 아니다");
        }
        return v.replace(":", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 템플릿의 자리표시자를 값으로 바꾼다.
     *
     * @throws IllegalArgumentException 값이 비었거나 셸에서 위험한 문자를 담은 경우
     * @throws IllegalStateException    템플릿에 안 채운 자리표시자가 남은 경우
     */
    public static String render(String template, Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) {
                throw new IllegalArgumentException("설치 스크립트 값이 비었다: " + e.getKey());
            }
            if (!SAFE_VALUE.matcher(v).matches()) {
                // 값 자체는 로그에 남기지 않는다. enroll secret 이 섞여 들어올 수 있다.
                throw new IllegalArgumentException("설치 스크립트 값에 쓸 수 없는 문자가 있다: " + e.getKey());
            }
        }

        // 한 번만 훑으며 바꾼다. 결과를 다시 훑으면 값에 심은 {{...}} 가 두 번째 바퀴에서 살아난다.
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String name = m.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException("설치 스크립트 자리표시자를 못 채웠다: " + name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
