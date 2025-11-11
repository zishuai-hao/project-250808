package com.zd.sdq.protocol;

import cn.hutool.core.lang.Tuple;
import lombok.Data;

/**
 * @author hzs
 * @date 2023/12/02
 */
@Data
public abstract class RequestProtocol<T> {
    RequestHeader header;
    T body;
    Tuple crc;
    String end = "end";

    public abstract RequestProtocol<T> decode(byte[] bytes);
}
