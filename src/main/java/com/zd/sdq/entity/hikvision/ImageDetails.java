package com.zd.sdq.entity.hikvision;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 图像或视频等资源的描述信息
 */
@Data
public class ImageDetails {

    /**
     * 资源传输类型: url, binary, base64 (必需)
     */
    @JsonProperty("resourcesContentType")
    private String resourcesContentType;

    /**
     * 资源标识ID或具体内容 (必需)
     * 当类型为binary时, 该值为Content-ID.
     * 当类型为url时, 该值为具体的URL.
     * 当类型为base64时, 该值为编码后的数据.
     */
    @JsonProperty("resourcesContent")
    private String resourcesContent;

}