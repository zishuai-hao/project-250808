package com.zd.sdq;

import cn.hutool.core.util.HexUtil;
import com.zd.sdq.util.ModbusRtuUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled
public class RadarWaterLevelTest {
    /**
     * JYHDQ-WLV-P01-001-02 41001 11 110300010001d75a
     * CBXH-WLV-P01-001-02 41000 12 120300010001d769
     * YDXH-WLV-P01-001-02 41002 14 140300010001d70f
     * DLJH-WLV-P01-001-02 41003 13 130300010001d6b8
     * ZYXH-WLV-P01-001-02 41004 15 150300010001d6de
     */
    @Test
    void testSendRadarCommand() {
//        byte[] query = ModbusRtuUtil.buildReadRequest("12", 0x0001, 0x0001);
        byte[] query = ModbusRtuUtil.buildReadRequest("15", 0x0001, 0x0001);
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
