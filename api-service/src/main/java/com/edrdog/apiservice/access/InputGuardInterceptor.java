package com.edrdog.apiservice.access;

import com.edrdog.apiservice.auth.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 쿼리 파라미터와 경로 변수를 컨트롤러 앞에서 한꺼번에 거른다(KISA EDR-BF-19.1). 값 길이, 제어 문자, 시간 범위(from/to).
 * 컨트롤러마다 따로 쓰면 새 API 가 빠뜨린다. 여기서는 새 API 도 자동으로 걸린다.
 */
@Component
public class InputGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.getParameterMap().forEach((name, values) -> {
            for (String v : values) {
                check(name, v);
            }
        });
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathVariables != null) {
            pathVariables.forEach(InputGuardInterceptor::check);
        }
        checkTimeRange(request.getParameter("from"), request.getParameter("to"));
        return true;
    }

    private static void check(String name, String value) {
        if (value.length() > InputRules.MAX_PARAM_LENGTH || InputRules.hasControlChars(value)) {
            // 값은 되돌려 주지 않는다. 이름만 알려 준다(이름도 길면 자른다).
            throw AuthException.invalidInput(
                    name.substring(0, Math.min(name.length(), 50)) + " 값이 너무 길거나 허용되지 않는 문자가 있습니다");
        }
    }

    /** from/to 는 epoch millis 다. 음수이거나 from 이 to 보다 크면 거절한다. 숫자가 아닌 값은 스프링의 타입 변환이 400 으로 막는다. */
    private static void checkTimeRange(String from, String to) {
        Long f = parseOrNull(from);
        Long t = parseOrNull(to);
        if ((f != null && f < 0) || (t != null && t < 0) || (f != null && t != null && f > t)) {
            throw AuthException.invalidInput("from, to 는 0 이상이고 from 이 to 보다 클 수 없습니다");
        }
    }

    private static Long parseOrNull(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
