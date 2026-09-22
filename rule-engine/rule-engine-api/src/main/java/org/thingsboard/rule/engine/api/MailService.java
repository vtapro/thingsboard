// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.rule.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.mail.javamail.JavaMailSender;
import org.thingsboard.server.common.data.ApiFeature;
import org.thingsboard.server.common.data.ApiUsageRecordState;
import org.thingsboard.server.common.data.ApiUsageStateValue;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;

public interface MailService {

    void updateMailConfiguration();

    void sendEmail(TenantId tenantId, String email, String subject, String message) throws ThingsboardException;

    void sendTestMail(JsonNode config, String email) throws ThingsboardException;

    default void sendActivationEmail(String activationLink, long ttlMs, String email) throws ThingsboardException {
        sendActivationEmail(TenantId.SYS_TENANT_ID, activationLink, ttlMs, email);
    }

    void sendActivationEmail(TenantId tenantId, String activationLink, long ttlMs, String email) throws ThingsboardException;

    default void sendAccountActivatedEmail(String loginLink, String email) throws ThingsboardException {
        sendAccountActivatedEmail(TenantId.SYS_TENANT_ID, loginLink, email);
    }

    void sendAccountActivatedEmail(TenantId tenantId, String loginLink, String email) throws ThingsboardException;

    default void sendResetPasswordEmail(String passwordResetLink, long ttlMs, String email) throws ThingsboardException {
        sendResetPasswordEmail(TenantId.SYS_TENANT_ID, passwordResetLink, ttlMs, email);
    }

    void sendResetPasswordEmail(TenantId tenantId, String passwordResetLink, long ttlMs, String email) throws ThingsboardException;

    default void sendResetPasswordEmailAsync(String passwordResetLink, long ttlMs, String email) {
        sendResetPasswordEmailAsync(TenantId.SYS_TENANT_ID, passwordResetLink, ttlMs, email);
    }

    void sendResetPasswordEmailAsync(TenantId tenantId, String passwordResetLink, long ttlMs, String email);

    default void sendPasswordWasResetEmail(String loginLink, String email) throws ThingsboardException {
        sendPasswordWasResetEmail(TenantId.SYS_TENANT_ID, loginLink, email);
    }

    void sendPasswordWasResetEmail(TenantId tenantId, String loginLink, String email) throws ThingsboardException;

    default void sendAccountLockoutEmail(String lockoutEmail, String email, Integer maxFailedLoginAttempts) throws ThingsboardException {
        sendAccountLockoutEmail(TenantId.SYS_TENANT_ID, lockoutEmail, email, maxFailedLoginAttempts);
    }

    void sendAccountLockoutEmail(TenantId tenantId, String lockoutEmail, String email, Integer maxFailedLoginAttempts) throws ThingsboardException;

    default void sendTwoFaVerificationEmail(String email, String verificationCode, int expirationTimeSeconds) throws ThingsboardException {
        sendTwoFaVerificationEmail(TenantId.SYS_TENANT_ID, email, verificationCode, expirationTimeSeconds);
    }

    void sendTwoFaVerificationEmail(TenantId tenantId, String email, String verificationCode, int expirationTimeSeconds) throws ThingsboardException;

    void send(TenantId tenantId, CustomerId customerId, TbEmail tbEmail) throws ThingsboardException;

    void send(TenantId tenantId, CustomerId customerId, TbEmail tbEmail, JavaMailSender javaMailSender, long timeout) throws ThingsboardException;

    default void sendApiFeatureStateEmail(ApiFeature apiFeature, ApiUsageStateValue stateValue, String email, ApiUsageRecordState recordState) throws ThingsboardException {
        sendApiFeatureStateEmail(TenantId.SYS_TENANT_ID, apiFeature, stateValue, email, recordState);
    }

    void sendApiFeatureStateEmail(TenantId tenantId, ApiFeature apiFeature, ApiUsageStateValue stateValue, String email, ApiUsageRecordState recordState) throws ThingsboardException;

    void testConnection(TenantId tenantId) throws Exception;

    boolean isConfigured(TenantId tenantId);

}
