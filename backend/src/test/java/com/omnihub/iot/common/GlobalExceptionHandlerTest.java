package com.omnihub.iot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every failure must leave the API as the documented envelope {code, message, data} with the matching HTTP status,
 * and internal details (SQL, exception text) must never reach the client.
 */
class GlobalExceptionHandlerTest {

    record Body(@NotBlank(message = "名称不能为空") String name, @Min(value = 1, message = "数量至少为1") int count) {
    }

    @RestController
    static class Probe {
        @PostMapping("/valid")
        String valid(@Valid @RequestBody Body b) {
            return "ok";
        }

        @GetMapping("/biz")
        String biz() {
            throw BizException.conflict("设备当前离线，无法下发指令");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("secret detail");
        }

        @GetMapping("/unauth")
        String unauth() {
            throw new BadCredentialsException("secret detail");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new RuntimeException("duplicate key value violates unique constraint \"uk_secret_index\""));
        }

        @GetMapping("/constraint")
        String constraint() {
            throw new ConstraintViolationException("size must be between 1 and 10", Set.of());
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("jdbc:postgresql://db:5432/iot password=hunter2");
        }

        @GetMapping("/param")
        String param(@RequestParam int n) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return Jsons.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void businessExceptionKeepsItsStatusAndMessage() throws Exception {
        MvcResult r = mvc.perform(get("/biz")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asInt()).isEqualTo(409);
        assertThat(json(r).get("message").asText()).isEqualTo("设备当前离线，无法下发指令");
    }

    @Test
    void validationErrorsListEveryBadFieldAndShowTheFirstAsMessage() throws Exception {
        MvcResult r = mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"count\":0}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(r);
        assertThat(body.get("code").asInt()).isEqualTo(400);
        assertThat(body.at("/data/name").asText()).isEqualTo("名称不能为空");
        assertThat(body.at("/data/count").asText()).isEqualTo("数量至少为1");
        assertThat(body.get("message").asText()).isIn("名称不能为空", "数量至少为1");
    }

    @Test
    void malformedAndMistypedInputIsA400WithAGenericMessage() throws Exception {
        for (MvcResult r : new MvcResult[] {
                mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{bad json")).andReturn(),
                mvc.perform(get("/param")).andReturn(), // missing required parameter
                mvc.perform(get("/param").param("n", "abc")).andReturn() }) {
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(json(r).get("message").asText()).isEqualTo("请求参数格式错误");
        }
    }

    @Test
    void securityExceptionsMapTo401And403WithoutLeakingDetails() throws Exception {
        MvcResult denied = mvc.perform(get("/denied")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(denied).get("message").asText()).isEqualTo("无权限执行该操作");

        MvcResult unauth = mvc.perform(get("/unauth")).andReturn();
        assertThat(unauth.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(unauth).get("message").asText()).isEqualTo("未登录或登录已过期");
        assertThat(denied.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret detail");
        assertThat(unauth.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret detail");
    }

    @Test
    void databaseConstraintViolationIsAConflictWithoutSqlDetails() throws Exception {
        MvcResult r = mvc.perform(get("/integrity")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).contains("数据冲突").doesNotContain("uk_secret_index").doesNotContain("duplicate key");
    }

    @Test
    void unexpectedErrorsBecomeAGeneric500() throws Exception {
        MvcResult r = mvc.perform(get("/boom")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(json(r).get("message").asText()).isEqualTo("服务器内部错误");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("hunter2")
                .doesNotContain("jdbc:");
    }

    @Test
    void wrongHttpMethodIsA405() throws Exception {
        MvcResult r = mvc.perform(post("/biz")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(405);
        assertThat(json(r).get("code").asInt()).isEqualTo(405);
    }

    @Test
    void constraintViolationFromMethodValidationIsA400() throws Exception {
        MvcResult r = mvc.perform(get("/constraint")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asInt()).isEqualTo(400);
    }
}
