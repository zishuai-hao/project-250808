package com.zd.sdq.service;

import cn.hutool.core.util.HexUtil;
import com.zd.sdq.util.ModbusRtuUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import java.util.Arrays;

/**
 * 雷达水位仪TCP服务器服务
 * @author hzs
 * @date 2024/01/01
 */
@Slf4j
@Service
public class RadarWaterLevelTcpServer {

    @Value("${radar.water.level.tcp.server.port:4024}")
    private int serverPort;

    @Value("${radar.water.level.tcp.server.max-connections:10}")
    private int maxConnections;

    @Value("${radar.water.level.tcp.server.send-interval-ms:2000}")
    private int sendIntervalMs;

    @Value("${radar.water.level.tcp.server.receive-timeout-ms:100}")
    private int receiveTimeoutMs;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // 线程跟踪
    private final ConcurrentHashMap<String, AtomicInteger> clientThreadCount = new ConcurrentHashMap<>();
    private final AtomicInteger totalActiveThreads = new AtomicInteger(0);

    /**
     * 启动TCP服务器
     */
    @PostConstruct
    public void start() {
        if (running) {
            log.warn("TCP服务器已在运行中");
            return;
        }

        try {
            serverSocket = new ServerSocket(serverPort);
            // 每个客户端需要3个线程：主处理线程 + 发送线程 + 接收线程
            int threadPoolSize = maxConnections * 3 + 5; // 额外预留5个线程
            executorService = Executors.newFixedThreadPool(threadPoolSize);
            running = true;

            log.info("雷达水位仪TCP服务器启动成功，监听端口: {}, 线程池大小: {}", serverPort, threadPoolSize);

            // 启动监听线程
            new Thread(this::acceptConnections, "RadarWaterLevel-TCP-Server").start();

        } catch (Exception e) {
            log.error("雷达水位仪TCP服务器启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止TCP服务器
     */
    @PreDestroy
    public void stop() {
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                log.info("雷达水位仪TCP服务器已停止");
            }
            
            if (executorService != null && !executorService.isShutdown()) {
                log.info("正在关闭线程池，当前活跃线程: {}", totalActiveThreads.get());
                executorService.shutdown();
                
                // 等待线程池优雅关闭
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("线程池未能在5秒内优雅关闭，强制关闭");
                    executorService.shutdownNow();
                    
                    // 再次等待
                    if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                        log.error("线程池强制关闭失败");
                    }
                }
                log.info("线程池已关闭");
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("关闭过程被中断: {}", e.getMessage());
            // 强制关闭
            if (executorService != null) {
                executorService.shutdownNow();
            }
        } catch (Exception e) {
            log.error("停止TCP服务器失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 接受客户端连接
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                log.info("客户端连接: {}", clientSocket.getInetAddress().getHostAddress());

                // 为每个客户端创建处理线程
                executorService.submit(() -> handleClient(clientSocket));

            } catch (Exception e) {
                if (running) {
                    log.error("接受客户端连接失败: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        
        try {
            log.info("开始处理客户端: {}", clientAddress);
            
            // 跟踪此客户端的线程数量
            clientThreadCount.put(clientAddress, new AtomicInteger(0));

            // 将发送和接收任务提交到线程池
            Future<?> senderFuture = executorService.submit(() -> {
                clientThreadCount.get(clientAddress).incrementAndGet();
                totalActiveThreads.incrementAndGet();
                try {
                    handleSender(clientSocket);
                } finally {
                    clientThreadCount.get(clientAddress).decrementAndGet();
                    totalActiveThreads.decrementAndGet();
                }
            });
            
            Future<?> receiverFuture = executorService.submit(() -> {
                clientThreadCount.get(clientAddress).incrementAndGet();
                totalActiveThreads.incrementAndGet();
                try {
                    handleReceiver(clientSocket);
                } finally {
                    clientThreadCount.get(clientAddress).decrementAndGet();
                    totalActiveThreads.decrementAndGet();
                }
            });

            // 等待发送和接收任务完成
            try {
                senderFuture.get();
                receiverFuture.get();
            } catch (Exception e) {
                log.warn("等待客户端处理任务完成时发生异常: {}", e.getMessage());
                // 取消未完成的任务
                senderFuture.cancel(true);
                receiverFuture.cancel(true);
            }

        } catch (Exception e) {
            log.error("处理客户端连接失败: {}", e.getMessage(), e);
        } finally {
            try {
                clientSocket.close();
                log.info("客户端连接已关闭: {}, 剩余活跃线程: {}", clientAddress, totalActiveThreads.get());
                // 清理线程计数
                clientThreadCount.remove(clientAddress);
            } catch (Exception e) {
                log.error("关闭客户端连接失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理发送指令（独立线程）
     */
    private void handleSender(Socket clientSocket) {
        try (OutputStream outputStream = clientSocket.getOutputStream()) {
            log.info("启动发送线程: {}", clientSocket.getInetAddress().getHostAddress());

            while (running && !clientSocket.isClosed()) {
                try {
                    // 构造并发送 Modbus 读保持寄存器请求: 01 03 00 01 00 01 D5 CA
                    byte[] query = ModbusRtuUtil.buildReadRequest("14", 0x0001, 0x0001);
                    outputStream.write(query);
                    outputStream.flush();
                    log.debug("发送查询水位指令: {}", HexUtil.encodeHexStr(query));

                    // 发送间隔（可配置）
                    Thread.sleep(sendIntervalMs); // 2秒间隔发送

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("发送线程被中断");
                    break;
                } catch (Exception e) {
                    log.error("发送指令失败: {}", e.getMessage(), e);
                    break;
                }
            }

        } catch (Exception e) {
            log.error("发送线程异常: {}", e.getMessage(), e);
        } finally {
            log.info("发送线程结束: {}", clientSocket.getInetAddress().getHostAddress());
        }
    }

    /**
     * 处理接收数据（独立线程）
     */
    private void handleReceiver(Socket clientSocket) {
        try (InputStream inputStream = clientSocket.getInputStream()) {
            log.info("启动接收线程: {}", clientSocket.getInetAddress().getHostAddress());

            while (running && !clientSocket.isClosed()) {
                try {
                    // 持续监听数据
                    byte[] resp = readRequest(inputStream);
                    if (resp != null && resp.length > 0) {
                        log.debug("收到响应: {}", HexUtil.encodeHexStr(resp));
                        
                        // CRC 校验
                        if (!ModbusRtuUtil.verifyCrc(resp)) {
                            log.warn("响应CRC校验失败");
                        } else {
                            // 解析水位
                            Double waterLevelMeter = parseModbusWaterLevel(resp);
                            if (waterLevelMeter != null) {
                                log.info("当前水位: {} m", waterLevelMeter);
                                // 这里可以添加数据处理逻辑，比如保存到数据库、发送MQTT等
                            } else {
                                log.warn("无法从响应中解析水位");
                            }
                        }
                    }

                    // 短暂休眠避免CPU占用过高
                    Thread.sleep(50);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("接收线程被中断");
                    break;
                } catch (Exception e) {
                    log.error("接收数据失败: {}", e.getMessage(), e);
                    break;
                }
            }

        } catch (Exception e) {
            log.error("接收线程异常: {}", e.getMessage(), e);
        } finally {
            log.info("接收线程结束: {}", clientSocket.getInetAddress().getHostAddress());
        }
    }

    /**
     * 读取客户端请求
     */
    private byte[] readRequest(InputStream inputStream) throws IOException {
        try {
            // 使用阻塞读取，等待数据到达
            if (inputStream.available() > 0 || waitForData(inputStream, receiveTimeoutMs)) {
                int available = inputStream.available();
                if (available > 0) {
                    byte[] buffer = new byte[available];
                    int size = inputStream.read(buffer);
                    if (size > 0) {
                        return Arrays.copyOf(buffer, size);
                    }
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("读取请求数据被中断: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * 等待数据到达
     */
    private boolean waitForData(InputStream inputStream, int timeoutMs) throws InterruptedException {
        int waited = 0;
        while (waited < timeoutMs) {
            try {
                if (inputStream.available() > 0) {
                    return true;
                }
            } catch (IOException e) {
                return false;
            }
            Thread.sleep(10);
            waited += 10;
        }
        return false;
    }

    /**
     * 解析 Modbus 响应中的水位，固定示例: 01 03 02 0B 99 7F 1E -> 2.969m
     * 规则：
     * - 功能码 0x03
     * - 字节数 0x02
     * - 数据为 2 字节无符号整数，单位毫米，需 /1000 转米
     */
    private Double parseModbusWaterLevel(byte[] resp) {
        try {
            if (resp.length < 7) {
                return null;
            }
            // 基本帧: 地址(1) 功能码(1) 字节数(1) 数据(2) CRC(2)
            byte addr = resp[0];
            byte func = resp[1];
            int byteCount = resp[2] & 0xFF;
            if (func != 0x03 || byteCount != 0x02) {
                return null;
            }
            int hi = resp[3] & 0xFF;
            int lo = resp[4] & 0xFF;
            int value = (hi << 8) | lo; // 0x0B99 = 2969
            // 2.969 m
            return value / 1000.0;
        } catch (Exception e) {
            log.warn("解析Modbus水位失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查服务器状态
     */
    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * 获取线程状态信息
     */
    public String getThreadStatus() {
        StringBuilder status = new StringBuilder();
        status.append("总活跃线程数: ").append(totalActiveThreads.get()).append("\n");
        status.append("客户端连接详情:\n");
        
        clientThreadCount.forEach((client, count) -> {
            status.append("  客户端 ").append(client).append(": ").append(count.get()).append(" 个线程\n");
        });
        
        if (executorService != null) {
            status.append("线程池状态: ");
            if (executorService.isShutdown()) {
                status.append("已关闭");
            } else if (executorService.isTerminated()) {
                status.append("已终止");
            } else {
                status.append("运行中");
            }
        }
        
        return status.toString();
    }
} 