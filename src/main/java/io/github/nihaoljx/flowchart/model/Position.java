package io.github.nihaoljx.flowchart.model;

/**
 * 节点坐标（任务43新增，可选字段）
 *
 * 为什么用 Double 不用 int？
 * 前端画布库 React Flow（任务44要用）的 position 就是浮点 {x, y}，
 * 自动布局算法算出来的坐标也带小数。
 *
 * 坐标从哪来？
 * - LLM 可选输出（schema 里声明为可选，通常不给）；
 * - 不给时由任务44前端自动布局计算，后端逻辑不受影响。
 */
public class Position {

    private Double x;   // 画布横坐标（像素）
    private Double y;   // 画布纵坐标（像素）

    /** 无参构造：Jackson 反序列化必需 */
    public Position() {
    }

    public Position(Double x, Double y) {
        this.x = x;
        this.y = y;
    }

    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }

    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
}
