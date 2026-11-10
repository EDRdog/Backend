package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.auth.SecurityPolicy;

public record SecurityPolicyResponse(int maxLoginFailures, int lockMinutes, int idleMinutes) {

    public static SecurityPolicyResponse from(SecurityPolicy p) {
        return new SecurityPolicyResponse(p.getMaxLoginFailures(), p.getLockMinutes(), p.getIdleMinutes());
    }
}
