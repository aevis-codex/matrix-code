package com.matrixcode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        MatrixCodeServerApplication.class,
        MatrixCodeServerApplicationTest.TestApiController.class
})
@AutoConfigureMockMvc
class MatrixCodeServerApplicationTest {

    private static final Path LOCAL_EXECUTION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-local-execution-app-" + System.nanoTime() + ".json");
    private static final Path RUNTIME_NOTIFICATION_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-runtime-notifications-app-" + System.nanoTime() + ".json");
    private static final Path WORKBENCH_STATE_STORAGE =
            Path.of(System.getProperty("java.io.tmpdir"), "matrixcode-test-workbench-state-app-" + System.nanoTime() + ".json");

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void persistentStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("matrixcode.local-execution.storage-path", LOCAL_EXECUTION_STORAGE::toString);
        registry.add("matrixcode.runtime-notifications.storage-path", RUNTIME_NOTIFICATION_STORAGE::toString);
        registry.add("matrixcode.workbench-state.storage-path", WORKBENCH_STATE_STORAGE::toString);
        registry.add("matrixcode.auth.require-sa-token", () -> "true");
        registry.add("matrixcode.auth.bootstrap-token", () -> "bootstrap-token");
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @AfterEach
    void cleanPersistentStorage() throws Exception {
        Files.deleteIfExists(LOCAL_EXECUTION_STORAGE);
        Files.deleteIfExists(RUNTIME_NOTIFICATION_STORAGE);
        Files.deleteIfExists(WORKBENCH_STATE_STORAGE);
    }

    @Test
    void 应用上下文可以启动() {
    }

    @Test
    void 强制SaToken模式下健康检查保持公开() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void 强制SaToken模式下静态资源保持公开() throws Exception {
        mockMvc.perform(get("/matrixcode-static-probe.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("matrixcode static probe\n"));
    }

    @Test
    void 强制SaToken模式下登录入口不被全局拦截() throws Exception {
        mockMvc.perform(post("/api/projects/demo/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user-dev"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    @Test
    void 强制SaToken模式下普通Api默认被全局拦截() throws Exception {
        mockMvc.perform(get("/api/internal/protected-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("缺少 Sa-Token 登录态"));
    }

    @RestController
    static class TestApiController {

        /**
         * 只用于验证全局 API 鉴权拦截器是否生效。
         *
         * <p>该接口刻意不调用任何业务权限守卫；如果强制 Sa-Token 模式下仍能访问，
         * 说明存在新控制器忘记接入权限守卫时被漏放的上线风险。</p>
         */
        @GetMapping("/api/internal/protected-probe")
        String protectedProbe() {
            return "ok";
        }
    }
}
