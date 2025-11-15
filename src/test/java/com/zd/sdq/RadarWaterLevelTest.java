package com.zd.sdq;

import cn.hutool.core.util.HexUtil;
import com.zd.sdq.util.ModbusRtuUtil;
import org.junit.jupiter.api.Test;


public class RadarWaterLevelTest {

    @Test
    void testSendRadarCommand() {
//        byte[] query = ModbusRtuUtil.buildReadRequest("12", 0x0001, 0x0001);
        byte[] query = ModbusRtuUtil.buildReadRequest("11", 0x0001, 0x0001);
        System.out.println(HexUtil.encodeHexStr(query));
    }

    @Test
    void testParseRadarResponse() {
        String response = "11 03 02 21 9B 20 7C ";
        double waterLevel = ModbusRtuUtil.parseWaterLevel(response);
        // Water Level: 10.485 m
        // Water Level: 18.769 m
        System.out.println("Water Level: " + waterLevel + " m");
    }
}
