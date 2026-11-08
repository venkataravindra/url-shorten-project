package com.example.agentic.repository;

import com.example.agentic.domain.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, Long> {
    List<GeneratedArtifact> findByWorkflowIdOrderByIdAsc(String workflowId);

    @Query("select coalesce(max(a.version), 0) from GeneratedArtifact a where a.workflowId = :wf and a.name = :name")
    int maxVersion(@Param("wf") String workflowId, @Param("name") String name);
}