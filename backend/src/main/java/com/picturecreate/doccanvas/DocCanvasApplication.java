package com.picturecreate.doccanvas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableRetry
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocCanvasApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocCanvasApplication.class, args);
    }
}
