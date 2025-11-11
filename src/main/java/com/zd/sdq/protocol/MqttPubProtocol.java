package com.zd.sdq.protocol;

import lombok.Data;

import java.util.List;

/**
 * Topic   bsms/{#bridge_code}/monitor/values
 *
 * @author hzs
 * @date 2023/12/02
 */
@Data
public class MqttPubProtocol {
    String cjy_no;

    List<ChannelData> channel_data;

    String bridge_code;


}

