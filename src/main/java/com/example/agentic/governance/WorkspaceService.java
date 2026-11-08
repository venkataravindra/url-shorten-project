package com.example.agentic.governance;

import com.example.agentic.config.AgenticProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The only place generated artifacts are written, confined to the configured workspace root. */
@Component
public class WorkspaceService {
    private static final int MAX_BYTES = 1_000_000;
    private final Path root;
    private final SecurityValidator validator;

    public WorkspaceService(AgenticProperties props, SecurityValidator validator) {
        this.root = Path.of(props.workspace().root());
        this.validator = validator;
    }

    /** Writes content as workspace/{workflowId}/v{version}/{name} and returns the relative path. */
    public String write(String workflowId, int version, String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new com.example.agentic.exception.ValidationFailureException("Artifact " + name + " exceeds 1 MB");
        }
        String relative = workflowId + "/v" + version + "/" + validator.validateArtifactName(name);
        Path target = validator.resolveInside(root, relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write artifact to workspace", e);
        }
        return relative;
    }
}