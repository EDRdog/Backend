package com.edrdog.apiservice.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 감사기록의 단일 진입점. 다른 코드는 {@link #record(AuditEntry)} 한 줄로 사건을 남긴다.
 * 읽기는 {@link #search}. 수정·삭제 메서드는 일부러 두지 않는다.
 */
@Service
public class AuditService {

    // 칸 길이 상한. 엔티티 칸 길이와 에이전트 감사기록 API 의 상한이 이 값을 쓴다.
    public static final int MAX_ACTOR = 255;
    public static final int MAX_TYPE = 64;
    public static final int MAX_TARGET = 255;
    public static final int MAX_DETAIL = 500;
    public static final int MAX_IP = 64;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repo;
    private final EntityManager em;
    private final TransactionTemplate requiresNew;

    public AuditService(AuditRepository repo, EntityManager em, PlatformTransactionManager tm) {
        this.repo = repo;
        this.em = em;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 사건 하나를 남긴다. 실패해도 던지지 않고 로그만 남긴다(기록 실패가 본 요청을 깨면 안 된다).
     * 호출한 쪽의 트랜잭션과 따로 커밋하므로 거기서 쓴 롤백에 휩쓸리지 않는다. 그래서 본 작업이 커밋된 뒤에 부른다.
     */
    public void record(AuditEntry e) {
        try {
            requiresNew.executeWithoutResult(s -> repo.save(toRecord(e, Instant.now())));
        } catch (RuntimeException ex) {
            // 사건 내용(대상, 상세)은 로그에 옮기지 않는다. 유형만 남긴다.
            log.error("감사기록 저장 실패 type={} err={}", e.type(), ex.toString());
        }
    }

    /** 여러 건을 한 트랜잭션으로 남긴다. record 와 달리 실패하면 던진다(에이전트가 받은 사건은 잃으면 안 되므로 호출자가 재시도하게 한다). */
    @Transactional
    public void recordAll(List<AuditEntry> entries) {
        Instant now = Instant.now();
        repo.saveAll(entries.stream().map(e -> toRecord(e, now)).toList());
    }

    /** tenant 안에서 조건에 맞는 기록을 시각순으로 한 페이지 읽는다. */
    @Transactional(readOnly = true)
    public AuditPage search(AuditFilter f) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<AuditRecord> q = cb.createQuery(AuditRecord.class);
        Root<AuditRecord> r = q.from(AuditRecord.class);
        // id 를 두 번째 키로 둬서 같은 시각의 기록도 페이지 사이에서 순서가 흔들리지 않게 한다.
        q.select(r).where(predicates(cb, r, f)).orderBy(
                f.ascending() ? cb.asc(r.get("ts")) : cb.desc(r.get("ts")),
                f.ascending() ? cb.asc(r.get("id")) : cb.desc(r.get("id")));
        // 한 건 더 읽어서 다음 페이지가 있는지만 판단한다.
        List<AuditRecord> rows = em.createQuery(q)
                .setFirstResult(f.offset()).setMaxResults(f.limit() + 1).getResultList();
        boolean hasMore = rows.size() > f.limit();

        Long total = null;
        if (f.withTotal()) {
            CriteriaQuery<Long> c = cb.createQuery(Long.class);
            Root<AuditRecord> cr = c.from(AuditRecord.class);
            total = em.createQuery(c.select(cb.count(cr)).where(predicates(cb, cr, f))).getSingleResult();
        }
        return new AuditPage(hasMore ? rows.subList(0, f.limit()) : rows, hasMore, total);
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<AuditRecord> r, AuditFilter f) {
        List<Predicate> p = new ArrayList<>();
        // 서버 전체 사건(자체시험·무결성)은 tenant 가 없어 어느 조직의 admin 에게나 보인다
        p.add(cb.or(cb.equal(r.get("tenantId"), f.tenantId()),
                cb.and(cb.isNull(r.get("tenantId")), cb.equal(r.get("actorType"), AuditActor.SYSTEM))));
        if (f.from() != null) {
            p.add(cb.greaterThanOrEqualTo(r.<Instant>get("ts"), f.from()));
        }
        if (f.to() != null) {
            p.add(cb.lessThanOrEqualTo(r.<Instant>get("ts"), f.to()));
        }
        if (f.type() != null) {
            p.add(cb.equal(r.get("eventType"), f.type()));
        }
        if (f.actor() != null) {
            p.add(cb.equal(r.get("actor"), f.actor()));
        }
        if (f.result() != null) {
            p.add(cb.equal(r.get("result"), f.result()));
        }
        return p.toArray(new Predicate[0]);
    }

    /** 칸 길이를 넘는 글자는 자른다. 넘기면 INSERT 가 실패해 사건이 통째로 사라진다. */
    private static AuditRecord toRecord(AuditEntry e, Instant now) {
        AuditEntry cut = new AuditEntry(e.ts() != null ? e.ts() : now, e.tenantId(), e.actorType(),
                e.actorUserId(), cut(e.actor(), MAX_ACTOR), cut(e.type(), MAX_TYPE), cut(e.target(), MAX_TARGET),
                e.result(), cut(e.detail(), MAX_DETAIL), cut(e.ip(), MAX_IP));
        return new AuditRecord(cut.ts(), cut);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
