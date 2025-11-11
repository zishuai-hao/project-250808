package com.zd.sdq.util;

import static java.lang.Byte.toUnsignedInt;

/**
 * @author hzs
 * @date 2023/12/02
 */
public class ByteUtil {
    public static String getString(byte[] bytes, int index, int i) {
        return new String(bytes, index, i - index).trim();
    }

    public static Integer getInt(byte[] bytes, int high, int low) {
        return toUnsignedInt(bytes[high]) << 8 | toUnsignedInt(bytes[low]);
    }
}
