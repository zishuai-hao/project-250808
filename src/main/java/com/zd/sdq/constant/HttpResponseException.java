package com.zd.sdq.constant;

/**
 * @author hzs
 * @date 2023/12/17
 */
public class HttpResponseException extends RuntimeException{
    private static final long serialVersionUID = 1L;
    private String code;
    private String msg;

    public HttpResponseException(String code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }

    public HttpResponseException(String msg) {
        super(msg);
        this.msg = msg;
    }

    public HttpResponseException(String msg, Throwable e) {
        super(msg, e);
        this.msg = msg;
    }

    public HttpResponseException(String code, String msg, Throwable e) {
        super(msg, e);
        this.code = code;
        this.msg = msg;
    }

    public HttpResponseException(String code, String msg, String message) {
        super(message);
        this.code = code;
        this.msg = msg;
    }

    public HttpResponseException(String code, String msg, String message, Throwable e) {
        super(message, e);
        this.code = code;
        this.msg = msg;
    }

    public HttpResponseException(String code, String msg, String message, Throwable e, boolean enableSuppression, boolean writableStackTrace) {
        super(message, e, enableSuppression, writableStackTrace);
        this.code = code;
        this.msg = msg;
    }
}
