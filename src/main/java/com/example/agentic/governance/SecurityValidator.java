package com.example.agentic.governance;

import com.example.agentic.exception.InvalidRequestException;
import com.example.agentic.exception.PolicyViolationException;
import com.example.agentic.exception.SecurityConcernException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Input and output guardrails. The platform never executes shell commands or generated code; this class
 * additionally refuses requirements that try to smuggle them in, rejects credentials, and confines paths.
 */
@Component
public class SecurityValidator {
    public static final int MIN_REQUIREMENT = 10;
    public static final int MAX_REQUIREMENT = 2000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SHELL = Pattern.compile(
            "(?i)(rm\\s+-rf|mkfs|:\\(\\)\\s*\\{|curl[^\\n]*\\|\\s*(ba)?sh|wget[^\\n]*\\|\\s*(ba)?sh|powershell\\s+-enc|"
                    + "cmd\\.exe\\s*/c|\\bsudo\\s+|;\\s*shutdown|\\$\\(|`[^`]+`)");
    private static final Pattern ARTIFACT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
    private static final Pattern PROJECT_PATH = Pattern.compile("^[A-Za-z0-9_./-]{1,200}$");

    public String validateRequirement(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("requirement must not be blank");
        }
        if (text.length() > MAX_REQUIREMENT || text.strip().length() < MIN_REQUIREMENT) {
            throw new InvalidRequestException("requirement must be " + MIN_REQUIREMENT + "-" + MAX_REQUIREMENT + " characters");
        }
        if (CONTROL.matcher(text).find()) {
            throw new InvalidRequestException("requirement contains control characters");
        }
        if (SHELL.matcher(text).find()) {
            throw new PolicyViolationException("Requirement contains shell-command patterns; the platform never executes shell commands");
        }
        if (SecretRedactor.containsSecret(text)) {
            throw new PolicyViolationException("Requirement appears to contain a credential; remove it and use configuration instead");
        }
        return text;
    }

    public void assertNoSecrets(String label, String content) {
        if (SecretRedactor.containsSecret(content)) {
            throw new SecurityConcernException("Credential-like content detected in " + label + "; output blocked");
        }
    }

    public String validateArtifactName(String name) {
        if (name == null || !ARTIFACT_NAME.matcher(name).matches() || name.contains("..")) {
            throw new SecurityConcernException("Illegal artifact name");
        }
        return name;
    }

    /** Paths of files a plan proposes to touch in the target project: relative, no traversal. */
    public String validateProjectPath(String path) {
        if (path == null || !PROJECT_PATH.matcher(path).matches() || path.startsWith("/") || path.contains("..")) {
            throw new SecurityConcernException("Planned file path is outside the approved project tree");
        }
        return path;
    }

    /** Resolves a relative path inside the workspace root or throws. */
    public Path resolveInside(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityConcernException("Path escapes the approved workspace");
        }
        return resolved;
    }
}