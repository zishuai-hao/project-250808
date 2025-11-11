package com.zd.sdq.service.device;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * @author hzs
 * @date 2023/12/10
 */
public class DeviceImport {
    public static void main(String[] args) {
        String fileName = "/Users/hzs/code/zhidiancekong/lijianshu/sdq-data-push/上地桥站点编号1208.xlsx";

        // 创建监听器
        DeviceExcelEntityListener listener = new DeviceExcelEntityListener();

        // 读取Excel文件
        EasyExcel.read(fileName, DeviceExcelEntity.class, listener).sheet().doRead();

        // 获取读取的数据列表
        List<DeviceExcelEntity> DeviceExcelEntityList = listener.getDeviceExcelEntityList();

        // 打印读取的数据
        for (DeviceExcelEntity DeviceExcelEntity : DeviceExcelEntityList) {
            System.out.println(DeviceExcelEntity);
        }
    }

    // 监听器类
    static class DeviceExcelEntityListener extends AnalysisEventListener<DeviceExcelEntity> {
        private List<DeviceExcelEntity> deviceExcelEntityList = new ArrayList<>();

        @Override
        public void invoke(DeviceExcelEntity DeviceExcelEntity, AnalysisContext analysisContext) {
            deviceExcelEntityList.add(DeviceExcelEntity);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext analysisContext) {
            // 数据读取完成后的操作（可选）
        }

        public List<DeviceExcelEntity> getDeviceExcelEntityList() {
            return deviceExcelEntityList;
        }
    }
}
