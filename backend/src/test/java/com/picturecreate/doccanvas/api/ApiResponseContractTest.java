package com.picturecreate.doccanvas.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.common.Result;

/**
 * 统一响应契约测试：扫描全部 @RestController，确保没有接口漏掉 Result 包装。
 *
 * 允许的例外只有两类，且必须同时满足：
 * 1. 204 无响应体接口：返回 void 且标注 @ResponseStatus(NO_CONTENT)；
 * 2. 二进制下载接口：返回 ResponseEntity<byte[]>，由浏览器/<img> 直接消费。
 */
@DisplayName("统一响应契约")
class ApiResponseContractTest {

    private static final String BASE_PACKAGE = "com.picturecreate.doccanvas.api";

    @Test
    @DisplayName("所有 JSON 接口都返回 Result，只放行 204 与二进制接口")
    void allJsonEndpointsReturnResult() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertThat(controllers).as("必须扫描到控制器，避免契约测试空跑").hasSizeGreaterThanOrEqualTo(14);

        List<String> violations = new ArrayList<>();
        int checkedMethods = 0;
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) {
                    continue;
                }
                checkedMethods++;
                if (!isAllowed(method)) {
                    violations.add(controller.getSimpleName() + "#" + method.getName()
                            + " -> " + method.getGenericReturnType().getTypeName());
                }
            }
        }

        assertThat(checkedMethods).as("必须检查到接口方法").isGreaterThanOrEqualTo(30);
        assertThat(violations).as("这些接口没有返回 Result，前端会拿到未包装的数据").isEmpty();
    }

    private List<Class<?>> scanControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            // 只检查主代码里的顶层控制器；测试类里的探针控制器不参与契约
            if (Modifier.isPublic(type.getModifiers()) && type.getEnclosingClass() == null) {
                controllers.add(type);
            }
        }
        return controllers;
    }

    private boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }

    private boolean isAllowed(Method method) {
        Class<?> returnType = method.getReturnType();
        if (Result.class.isAssignableFrom(returnType)) {
            return true;
        }
        if (returnType == void.class && isNoContent(method)) {
            return true;
        }
        return isBinaryResponse(method);
    }

    private boolean isNoContent(Method method) {
        ResponseStatus status = method.getAnnotation(ResponseStatus.class);
        return status != null && status.value() == HttpStatus.NO_CONTENT;
    }

    private boolean isBinaryResponse(Method method) {
        if (method.getReturnType() != ResponseEntity.class) {
            return false;
        }
        Type genericType = method.getGenericReturnType();
        if (!(genericType instanceof ParameterizedType parameterized)) {
            return false;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 1 && arguments[0] == byte[].class;
    }
}
