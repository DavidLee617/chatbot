package com.lee.psyqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 入口。三种模式：
 * <pre>
 *   java -jar psyqa.jar ingest [--book file]   导入知识库后退出
 *   java -jar psyqa.jar ask "问题"              单轮问答后退出
 *   java -jar psyqa.jar                         启动 Web 服务（8080）
 * </pre>
 * ingest / ask 不启动 Web 服务器（≈ 用 Host 而不是 WebApplication）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PsyQaApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PsyQaApplication.class);
        if (args.length == 0) {
            app.run(args);
            return;
        }
        app.setWebApplicationType(WebApplicationType.NONE);
        // CLI 跑完就退出：Milvus 的 gRPC 线程不是守护线程，不显式 exit 进程不会结束
        System.exit(SpringApplication.exit(app.run(args)));
    }
}
