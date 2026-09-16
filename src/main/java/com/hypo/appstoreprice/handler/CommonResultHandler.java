package com.hypo.appstoreprice.handler;

import com.hypo.appstoreprice.pojo.bean.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一返回值处理
 *
 * @author hypo
 * @date 2022-01-08
 */
@Slf4j
@ControllerAdvice
public class CommonResultHandler implements ResponseBodyAdvice<Object> {

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(selectedContentType)) return body;
        if (body instanceof R) {
            return body;
        } else {
            return R.ok(body);
        }
    }

}
