package com.edrdog.apiservice.auth.web;

/** 셋 다 필수다. 빠지면 400. */
public record SecurityPolicyRequest(Integer maxLoginFailures, Integer lockMinutes, Integer idleMinutes) {
}
