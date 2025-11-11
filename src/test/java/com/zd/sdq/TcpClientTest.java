package com.zd.sdq;

import cn.hutool.core.util.HexUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;

/**
 * @author hzs
 * @date 2023/06/16
 */
@Disabled
public class TcpClientTest {

    @Test
    void sendTest() throws IOException, InterruptedException {
        final Socket socket = new Socket();
        final SocketAddress socketAddress = new InetSocketAddress("localhost", 41000);
        socket.connect(socketAddress);
        final InputStream in = socket.getInputStream();
        final OutputStream outputStream = socket.getOutputStream();
        while (true) {
            Thread.sleep(5000);




            // 风速采集回复指令示例：01 80 04 00 c2 01 00 00 6D F3
//                final byte[] decode = HexBin.decode("01800400c20100006DF3");

                // 风向采集回复指令示例：01 81 04 00 00 00 0e 01 D4 EB
//                final byte[] decode = HexBin.decode("0181040000000e01D4EB");

//            显示数据回复指令示例:07 03 02 FF DF 30 2C 返回偏移量为-3.3 mm

            final byte[] decode = HexUtil.decodeHex("0103020B997F1E");


//            final byte[] decode = HexBin.decode("01030400001111F8AF");
//            final byte[] decode = HexBin.decode("02030841D4FEAA44CCB3B6E9A9");

//            final byte[] decode = HexBin.decode("0103020000B844");
//            f7 60 07 5e 0a c0 01 80 00 00 00 00 2b46

                outputStream.write(decode);
                outputStream.flush();
                System.out.println("消息已发送" + Arrays.toString(decode));
//            final int available = in.available();
//            byte[] buffer = new byte[available];
//            int size = in.read(buffer);
//            if (size > 0) {
//                System.out.println("recv data: "  + HexBin.encode(buffer));
//            }

        }
    }
}
