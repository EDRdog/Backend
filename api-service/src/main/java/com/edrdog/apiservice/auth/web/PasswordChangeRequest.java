package com.edrdog.apiservice.auth.web;

public record PasswordChangeRequest(String currentPassword, String newPassword) {
}
