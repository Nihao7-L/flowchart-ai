package io.github.nihaoljx.flowchart;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

public class PlantUmlTest {
    public static void main(String[] args) throws Exception {
        // 硬编码一段 PlantUML 语法
        String plantUmlCode = """
            @startuml
            start
            :用户输入账号密码;
            if (验证通过?) then (是)
              :进入首页;
              stop
            else (否)
              :提示错误;
            endif
            @enduml
            """;

        // 渲染为 SVG
        SourceStringReader reader = new SourceStringReader(plantUmlCode);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        reader.outputImage(os, new FileFormatOption(FileFormat.SVG));
        String svg = os.toString("UTF-8");

        // 保存到桌面看看效果
        String desktop = System.getProperty("user.home") + "/Desktop/test_flowchart.svg";
        try (FileOutputStream fos = new FileOutputStream(desktop)) {
            fos.write(svg.getBytes("UTF-8"));
        }
        System.out.println("SVG 已保存到: " + desktop);
        System.out.println("SVG 前 200 字符: " + svg.substring(0, Math.min(200, svg.length())));
    }
}
