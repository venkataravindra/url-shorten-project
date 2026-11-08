package com.example.agentic.orchestration;

import com.example.agentic.domain.FailureKind;
import com.example.agentic.exception.ApiException;
import com.example.agentic.exception.PolicyViolationException;
import com.example.agentic.exception.TransientAgentException;
import com.example.agentic.exception.ValidationFailureException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;

/** Decides whether a failure is worth retrying. Only TRANSIENT failures are retried. */
@Component
public class FailureClassifier {
    public FailureKind classify(Throwable t) {
        if (t instanceof PolicyViolationException) {
            return FailureKind.POLICY;
        }
        if (t instanceof ValidationFailureException || t instanceof ConstraintViolationException
                || t instanceof IllegalArgumentException || t instanceof ApiException) {
            return FailureKind.VALIDATION;
        }
        if (t instanceof TransientAgentException || t instanceof TransientDataAccessException
                || t instanceof ConcurrencyFailureException || t instanceof SocketTimeoutException) {
            return FailureKind.TRANSIENT;
        }
        return FailureKind.PERMANENT;
    }
}