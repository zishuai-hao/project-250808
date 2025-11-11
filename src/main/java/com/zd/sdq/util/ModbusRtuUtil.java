package com.zd.sdq.util;

public class ModbusRtuUtil {


    public static double parseWaterLevel(String modbusHexResponse) {
        if (modbusHexResponse == null || modbusHexResponse.isEmpty()) {
            throw new IllegalArgumentException("响应报文不能为空。");
        }

        // 移除所有可能存在的空格，便于处理
        String cleanHex = modbusHexResponse.replaceAll("\\s+", "");

        // 校验报文长度是否足够获取数据区
        // 地址(2) + 功能码(2) + 字节数(2) + 数据(4) = 至少10个字符
        if (cleanHex.length() < 10) {
            throw new IllegalArgumentException("响应报文长度不足，无法解析。");
        }

        try {
            // 1. 提取核心数据: 数据从第7个字符开始（索引为6），长度为4。
            // 响应: 01 03 02 [0B 99] 7F 1E
            // 索引: 01 23 45 [67 89]
            String dataHex = cleanHex.substring(6, 10);

            // 2. 将16进制数据转换为10进制整数 (0B99 -> 2969)
            int waterLevelInMm = Integer.parseInt(dataHex, 16);

            // 3. 单位换算: 将毫米(mm)转换为米(m)
            return waterLevelInMm / 1000.0;

        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("解析数据失败，请检查响应报文格式。", e);
        }
    }

    public static byte[] buildReadRequest(String slaveId, int reg, int baseAddress) {
        if (slaveId == null || slaveId.isEmpty() || reg < 0 || baseAddress < 0) {
            throw new IllegalArgumentException("从站ID、寄存器地址和基地址不能为空且必须为非负整数。");
        }

        byte[] frame = new byte[8];
        // 从站ID (2-hex)
        frame[0] = (byte) Integer.parseInt(slaveId, 16);
        // 功能码 (2-hex)
        frame[1] = 0x03; // 读取保持寄存器的功能码
        // 寄存器地址 (4-hex)
        frame[2] = (byte) (reg >> 8);
        frame[3] = (byte) reg;
        // 基地址
        frame[4] = (byte) (baseAddress >> 8);
        frame[5] = (byte) baseAddress;

        int crc = crc16(frame, 6);
        frame[6] = (byte) (crc & 0xFF);        // CRC low
        frame[7] = (byte) ((crc >> 8) & 0xFF); // CRC high
        return frame;
    }

    public static boolean verifyCrc(byte[] data) {
        if (data == null || data.length < 3) {
            return false;
        }
        int len = data.length;
        int expected = ((data[len - 1] & 0xFF) << 8) | (data[len - 2] & 0xFF);
        int actual = crc16(data, len - 2);
        return expected == actual;
    }

    /**
     * Modbus RTU CRC16 (poly 0xA001, init 0xFFFF, little-endian)
     */
    public static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = (crc >>> 1);
                }
            }
        }
        return crc & 0xFFFF;
    }
} 