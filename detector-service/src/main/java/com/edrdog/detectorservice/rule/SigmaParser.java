package com.edrdog.detectorservice.rule;

import com.edrdog.detectorservice.dto.Alert;
import com.edrdog.schema.Event;
import com.edrdog.schema.EventTypes;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sigma 룰 한 개를 읽어 SigmaRule 로 만든다. 전체 Sigma 가 아니라 이 에이전트가 보내는 필드로 판정되는 부분집합이다.
 *
 * <p>지원: logsource.category 넷(process_creation, file_event, dns_query, network_connection), 필드 modifier
 * contains/startswith/endswith/all, 값 목록(OR), 맵 목록 selection(OR), 대소문자 무시, condition 의
 * and/or/not/괄호/{@code 1 of X*}/{@code all of X*}/them.
 *
 * <p>그 밖의 구문(re, windash, 와일드카드 값, 키워드 selection, 집계 condition, 에이전트가 안 보내는 필드)은
 * IllegalArgumentException 으로 거절한다. 조용히 무시하면 룰이 의도보다 넓게 또는 아예 안 걸리게 된다.
 */
public final class SigmaParser {

    private SigmaParser() {
    }

    /** logsource.category 가 가리키는 이벤트 type 들과, Sigma 필드가 이벤트의 어느 칸인지. */
    private record Category(Set<String> types, Map<String, Function<Event, String>> fields) {
    }

    // 에이전트는 인터프리터 실행(powershell, cmd, bash 등)을 script 타입으로 보내서 process_creation 은 둘 다 본다.
    private static final Map<String, Category> CATEGORIES = Map.of(
            "process_creation", new Category(Set.of(EventTypes.PROCESS, EventTypes.SCRIPT), Map.of(
                    "Image", Event::getProcess,
                    "ParentImage", Event::getParent,
                    "CommandLine", Event::getCmdline)),
            "file_event", new Category(Set.of(EventTypes.FILE), Map.of(
                    "TargetFilename", Event::getCmdline)),
            "dns_query", new Category(Set.of(EventTypes.DNS), Map.of(
                    "QueryName", Event::getDomain,
                    "Image", Event::getProcess)),
            "network_connection", new Category(Set.of(EventTypes.NETWORK), Map.of(
                    "DestinationIp", Event::getDestIp,
                    "DestinationPort", e -> Integer.toString(e.getDestPort()),
                    "Image", Event::getProcess)));

    /** 에이전트가 경로 없이 파일명만 보내는 필드 (docs/agent-protocol.md). */
    private static final Set<String> BASENAME_FIELDS = Set.of("Image", "ParentImage");

    private static final Set<String> MATCH_MODIFIERS = Set.of("contains", "startswith", "endswith");

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    private static final Pattern MITRE_TAG = Pattern.compile("attack\\.(t\\d{4}(?:\\.\\d{3})?)");

    public static SigmaRule parse(String yaml) {
        // 같은 키가 두 번 나오면 뒤의 것이 앞의 것을 조용히 덮어 룰이 의도와 다르게 걸린다. 파싱 단계에서 거절한다.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> doc = map(new Yaml(new SafeConstructor(options)).load(yaml), "룰 파일 최상위");

        String name = string(doc, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name 은 소문자, 숫자, _ 만 쓴다: " + name);
        }
        String category = string(map(doc.get("logsource"), "logsource"), "category");
        Category c = CATEGORIES.get(category);
        if (c == null) {
            throw new IllegalArgumentException("지원하지 않는 logsource.category: " + category);
        }

        Map<?, ?> detection = map(doc.get("detection"), "detection");
        Map<String, Predicate<Event>> selections = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : detection.entrySet()) {
            if (!"condition".equals(e.getKey())) {
                selections.put(String.valueOf(e.getKey()), selection(e.getValue(), c));
            }
        }
        Predicate<Event> match = new ConditionParser(string(detection, "condition"), selections).parse();

        return new SigmaRule(name.toUpperCase(Locale.ROOT), mitre(doc), severity(string(doc, "level"), category), c.types(), match);
    }

    /** tags 의 첫 attack.tNNNN 이 알림의 MITRE 가 된다. 없으면 알림에 기법을 못 싣는다. */
    private static String mitre(Map<?, ?> doc) {
        if (doc.get("tags") instanceof List<?> tags) {
            for (Object tag : tags) {
                Matcher m = MITRE_TAG.matcher(String.valueOf(tag));
                if (m.matches()) {
                    return m.group(1).toUpperCase();
                }
            }
        }
        throw new IllegalArgumentException("tags 에 MITRE 기법(attack.tNNNN)이 없다");
    }

    /** low 이하는 MEDIUM(알림만), high 는 HIGH(프로세스 종료 권고), critical 은 CRITICAL. */
    private static String severity(String level, String category) {
        String severity = switch (level) {
            case "informational", "low", "medium" -> Alert.SEV_MEDIUM;
            case "high" -> Alert.SEV_HIGH;
            case "critical" -> Alert.SEV_CRITICAL;
            default -> throw new IllegalArgumentException("지원하지 않는 level: " + level);
        };
        // 종료(kill)는 프로세스가 대상이다. 파일, DNS, 연결 이벤트로는 끌 프로세스를 특정할 수 없어 알림만 보낸다.
        return "process_creation".equals(category) ? severity : Alert.SEV_MEDIUM;
    }

    // ---- selection ----

    private static Predicate<Event> selection(Object def, Category c) {
        if (def instanceof Map<?, ?> m) {
            return fields(m, c);
        }
        if (def instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Map.class::isInstance)) {
            return list.stream().map(m -> fields((Map<?, ?>) m, c)).reduce(Predicate::or).orElseThrow();
        }
        throw new IllegalArgumentException("selection 은 필드 맵이거나 맵 목록이어야 한다 (키워드 검색은 지원하지 않는다)");
    }

    private static Predicate<Event> fields(Map<?, ?> m, Category c) {
        if (m.isEmpty()) {
            throw new IllegalArgumentException("비어 있는 selection");
        }
        return m.entrySet().stream()
                .map(e -> field(String.valueOf(e.getKey()), e.getValue(), c))
                .reduce(Predicate::and).orElseThrow();
    }

    /** {@code Field|modifier|...: 값 또는 값 목록} 한 칸. */
    private static Predicate<Event> field(String key, Object value, Category c) {
        List<String> parts = Arrays.asList(key.split("\\|"));
        String fieldName = parts.get(0);
        Function<Event, String> getter = c.fields().get(fieldName);
        if (getter == null) {
            throw new IllegalArgumentException("이 category 에서 지원하지 않는 필드: " + fieldName);
        }
        List<String> modifiers = parts.subList(1, parts.size());
        for (String m : modifiers) {
            if (!MATCH_MODIFIERS.contains(m) && !m.equals("all")) {
                throw new IllegalArgumentException("지원하지 않는 modifier: " + m + " (" + key + ")");
            }
        }
        boolean all = modifiers.contains("all");
        List<String> matches = modifiers.stream().filter(MATCH_MODIFIERS::contains).toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("일치 modifier 는 하나만 쓴다: " + key);
        }
        String match = matches.isEmpty() ? "equals" : matches.get(0);
        boolean basename = BASENAME_FIELDS.contains(fieldName);

        List<?> values = value instanceof List<?> list ? list : Arrays.asList(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("값이 비어 있다: " + key);
        }
        List<Predicate<String>> tests = new ArrayList<>();
        for (Object v : values) {
            tests.add(text(match, v, basename));
        }
        return e -> {
            String actual = getter.apply(e).toLowerCase(Locale.ROOT);
            return all ? tests.stream().allMatch(t -> t.test(actual)) : tests.stream().anyMatch(t -> t.test(actual));
        };
    }

    private static Predicate<String> text(String match, Object v, boolean basename) {
        if (!(v instanceof String || v instanceof Number)) {
            throw new IllegalArgumentException("값은 문자열이나 숫자여야 한다: " + v);
        }
        String s = v.toString().toLowerCase(Locale.ROOT);
        // 빈 값의 contains/startswith/endswith 는 모든 이벤트에 맞는다. 룰을 쓰다 흘린 값이 전부 걸리는 룰이 된다.
        if (s.isEmpty() && !match.equals("equals")) {
            throw new IllegalArgumentException("빈 값은 " + match + " 에 쓸 수 없다 (전부 맞는다)");
        }
        if (s.contains("*") || s.contains("?")) {
            throw new IllegalArgumentException("와일드카드(* ?)는 지원하지 않는다. contains/startswith/endswith 로 쓴다: " + v);
        }
        int sep = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        if (basename && sep >= 0) {
            // 에이전트는 경로 없이 파일명만 보낸다. 경로가 붙은 값은 파일명 일치로 줄여서 본다.
            if (!match.equals("equals") && !match.equals("endswith")) {
                throw new IllegalArgumentException("에이전트는 Image 경로를 보내지 않아 경로 조각 검색은 못 한다: " + v);
            }
            String name = s.substring(sep + 1);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("빈 값: 경로 구분자 뒤에 파일명이 없다: " + v);
            }
            return name::equals;
        }
        return switch (match) {
            case "contains" -> a -> a.contains(s);
            case "startswith" -> a -> a.startsWith(s);
            case "endswith" -> a -> a.endsWith(s);
            default -> s::equals;
        };
    }

    // ---- condition ----

    /** or → and → not → 원자 순서의 재귀 하강. 우선순위는 Sigma 와 같다(not > and > or). */
    private static final class ConditionParser {
        private final List<String> tokens;
        private final Map<String, Predicate<Event>> selections;
        private int pos;

        ConditionParser(String condition, Map<String, Predicate<Event>> selections) {
            this.tokens = Arrays.asList(condition.replace("(", " ( ").replace(")", " ) ").trim().split("\\s+"));
            this.selections = selections;
        }

        Predicate<Event> parse() {
            Predicate<Event> p = or();
            if (pos < tokens.size()) {
                throw new IllegalArgumentException("condition 에서 해석할 수 없는 부분: " + tokens.get(pos));
            }
            return p;
        }

        private Predicate<Event> or() {
            Predicate<Event> p = and();
            while (peek("or")) {
                pos++;
                p = p.or(and());
            }
            return p;
        }

        private Predicate<Event> and() {
            Predicate<Event> p = not();
            while (peek("and")) {
                pos++;
                p = p.and(not());
            }
            return p;
        }

        private Predicate<Event> not() {
            if (peek("not")) {
                pos++;
                return not().negate();
            }
            return atom();
        }

        private Predicate<Event> atom() {
            String t = next();
            if (t.equals("(")) {
                Predicate<Event> p = or();
                if (!next().equals(")")) {
                    throw new IllegalArgumentException("condition 의 괄호가 닫히지 않았다");
                }
                return p;
            }
            if ((t.equals("1") || t.equals("all")) && peek("of")) {
                pos++;
                return quantified(t.equals("all"), next());
            }
            Predicate<Event> p = selections.get(t);
            if (p == null) {
                throw new IllegalArgumentException("condition 이 모르는 이름: " + t);
            }
            return p;
        }

        /** {@code 1 of sel*} / {@code all of sel*} / {@code them}. */
        private Predicate<Event> quantified(boolean all, String pattern) {
            List<Predicate<Event>> hits = selections.entrySet().stream()
                    .filter(e -> pattern.equals("them")
                            || (pattern.endsWith("*") ? e.getKey().startsWith(pattern.substring(0, pattern.length() - 1))
                            : e.getKey().equals(pattern)))
                    .map(Map.Entry::getValue)
                    .toList();
            if (hits.isEmpty()) {
                throw new IllegalArgumentException("condition 의 " + pattern + " 에 해당하는 selection 이 없다");
            }
            return all ? hits.stream().reduce(Predicate::and).orElseThrow()
                    : hits.stream().reduce(Predicate::or).orElseThrow();
        }

        private boolean peek(String token) {
            return pos < tokens.size() && tokens.get(pos).equals(token);
        }

        private String next() {
            if (pos >= tokens.size()) {
                throw new IllegalArgumentException("condition 이 중간에 끝났다");
            }
            return tokens.get(pos++);
        }
    }

    // ---- 타입 확인 ----

    private static Map<?, ?> map(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(what + " 은 맵이어야 한다");
    }

    private static String string(Map<?, ?> m, String key) {
        if (m.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(key + " 가 없거나 문자열이 아니다");
    }
}
