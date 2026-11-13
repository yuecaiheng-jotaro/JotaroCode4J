package org.jotaro.jotarocode.exception;

import lombok.Getter;

/** 调用方能看懂的失败，例如会话不存在、内容为空、运行中不能重置。不要用它包装供应商的未知错误。 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 400;
    }
}
