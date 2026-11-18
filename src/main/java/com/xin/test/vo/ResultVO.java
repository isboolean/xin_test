package com.xin.test.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 统一响应 VO
 *
 * @param <T> 数据类型
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResultVO<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> ResultVO<T> success(T data) {
        return new ResultVO<>(200, "success", data);
    }

    public static <T> ResultVO<T> fail(Integer code, String message) {
        return new ResultVO<>(code, message, null);
    }
}
