package io.github.nihaoljx.flowchart.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 下载图表请求体 */
@Schema(description = "下载图表请求")
public record DownloadRequest(
        @Schema(description = "PlantUML 源码", example = "@startuml\nstart\n:hi;\nstop\n@enduml")
        String plantUml,

        @Schema(description = "格式：svg / png", example = "png")
        String format
) {
}
