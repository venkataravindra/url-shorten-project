package com.example.agentic;

import com.example.agentic.llm.FaultInjector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    public static final String GREENFIELD = "Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics.";
    public static final String BROWNFIELD = "Enhance the existing URL shortener to support URL expiration and automatic deactivation of expired links.";
    public static final String AMBIGUOUS = "Improve the URL shortener performance and make it more reliable.";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected FaultInjector faults;

    @AfterEach
    void clearInjectedFaults() {
        faults.clear();
    }

    protected RequestPostProcessor engineer() { return httpBasic("engineer", "engineer123"); }
    protected RequestPostProcessor approver() { return httpBasic("approver", "approver123"); }
    protected RequestPostProcessor admin() { return httpBasic("admin", "admin123"); }

    protected JsonNode body(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    protected String createWorkflow(String requirement) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/workflows").with(engineer()).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("requirement", requirement)))).andExpect(status().isCreated()).andReturn();
        return body(r).get("id").asText();
    }

    protected JsonNode execute(String id) throws Exception {
        return body(mvc.perform(post("/api/v1/workflows/" + id + "/execute").with(engineer())).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode approve(String id) throws Exception {
        return body(mvc.perform(post("/api/v1/workflows/" + id + "/approve").with(approver()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"looks good\"}")).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode workflow(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/workflows/" + id).with(engineer())).andExpect(status().isOk()).andReturn());
    }

    /** Task key -> task JSON (outputs included). */
    protected Map<String, JsonNode> tasks(String id) throws Exception {
        JsonNode arr = body(mvc.perform(get("/api/v1/workflows/" + id + "/tasks?includeOutput=true").with(engineer()))
                .andExpect(status().isOk()).andReturn());
        Map<String, JsonNode> map = new LinkedHashMap<>();
        arr.forEach(t -> map.put(t.get("taskKey").asText(), t));
        return map;
    }

    protected JsonNode getJson(String url) throws Exception {
        return body(mvc.perform(get(url).with(engineer())).andExpect(status().isOk()).andReturn());
    }
}