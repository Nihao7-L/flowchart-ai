package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.model.CanvasSyncRequest;
import io.github.nihaoljx.flowchart.model.ChatRequest;
import io.github.nihaoljx.flowchart.model.Result;
import io.github.nihaoljx.flowchart.service.GenerationBudget;
import io.github.nihaoljx.flowchart.service.GenerationService;
import io.github.nihaoljx.flowchart.session.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图表接口
 *
 * M1 补入 POST /api/chat（SSE）承载
 * "文字 → 图表 IR"主链路；
 * PUT /api/model/canvas 承载
 * "用户拖拽 / 删除 / 手绘 → 画布现状同步"。
 */
@Tag(name = "图表接口",
        description = "健康检查 + 文字→图表 IR "
                + "生成 + 画布现状同步")
@RestController
public class DiagramController {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    DiagramController.class);

    /**
     * SSE 通道存活上限（毫秒）
     *
     * 由 totalBudgetMs() 推导，两者必须恒等对齐：
     * 通道若先于生成结束被 Spring 掐断，前端只能收到一条被腰斩的流
     * （现象：聊天栏说"已生成 N 个元素"却看不到图，或干脆毫无提示）。
     * 写死 120 秒，而实测单轮 LLM 调用就要约 100 秒——第 2 轮必然撞墙。
     *
     * ⚠️  改：原来直接写 `GenerationBudget.TOTAL_BUDGET_MS + 20_000L`，
     * 那是**编译期常量表达式**，javac 会内联成字面量写进本 class，运行时不再回读
     * GenerationBudget。后果是"只改了 TOTAL_BUDGET_MS、本文件没动"时增量编译
     * 不会重编译本类 ⇒ 通道上限静默停在旧值（例如预算 30 分钟、通道 15 分钟，
     * 第 15 分钟被腰斩）。改成**方法调用**后不再是编译期常量，不会内联。
     */
    private static final long SSE_TIMEOUT_MS =
            GenerationBudget.totalBudgetMs() + 20_000L;

    private final GenerationService generationService;
    private final SessionStore sessionStore;

    /** 生成任务执行线程池（受管、可复用，见 AsyncConfig） */
    private final TaskExecutor generationExecutor;

    public DiagramController(
            GenerationService generationService,
            SessionStore sessionStore,
            TaskExecutor generationExecutor) {
        this.generationService = generationService;
        this.sessionStore = sessionStore;
        this.generationExecutor = generationExecutor;
    }

    @Operation(summary = "健康检查")
    @GetMapping("/api/health")
    public Result<Void> health() {
        return Result.success();
    }

    /**
     * 文字 → 图表 IR（SSE 流式推送）
     *
 * 事件类型：thinking / validation
     * / result / error
     *
 * 会话语义：若 `sessionId` 对应的会话里已有模型，
     * 本次请求按"改这张图"处理（当前场景会进 prompt）；否则为新建。
     * 空 sessionId = 无状态生成，只为兼容，不参与上下文与落库。
     */
    @Operation(summary = "生成白板内容（SSE）",
            description = "发送用户需求，实时推送 "
                    + "thinking/validation/result/error")
    @PostMapping(value = "/api/chat",
            produces = MediaType
                    .TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestBody ChatRequest request) {

        SseEmitter emitter =
                new SseEmitter(SSE_TIMEOUT_MS);

        // sessionId 必须透传：生成服务靠它取"当前图模型"做编辑上下文，
        // 并在生成成功后把新模型写回。
        // P0 #2：`new Thread().start()` 是无界裸线程（不可复用、不得
        // 回收、客户端一多线程数即失控）；改为受管线程池执行。
        // P0 取消（1.2 修法）：三个回调（完成/超时/出错）都置取消标志；
        // 生成循环在每一次 LLM 调用**之前**读它，客户端一旦断开就在
        // 下一轮前主动早退（CANCELLED），不再烧 token 跑到预算上限。
        AtomicBoolean cancel = new AtomicBoolean(false);
        generationExecutor.execute(() ->
                generationService.generate(
                        request.sessionId(),
                        request.message(), emitter,
                        cancel));
        emitter.onCompletion(() -> {
            cancel.set(true);
            LOG.info("SSE 通道完成: sessionId={}",
                    request.sessionId());
        });
        emitter.onTimeout(() -> {
            cancel.set(true);
            LOG.warn("SSE 通道超时: sessionId={}",
                    request.sessionId());
        });
        emitter.onError(error -> {
            cancel.set(true);
            LOG.warn("SSE 通道错误: sessionId={} error={}",
                    request.sessionId(),
                    error.getMessage());
        });

        return emitter;
    }

    /**
     * 画布现状同步（，取代原 PATCH /api/model/positions）
     *
 * 为什么从"坐标回写"升级成"现状同步"：只回写坐标时，
     * "用户删了哪些元素""用户手绘了哪些元素""用户拖解绑了哪些线"
     * 后端一无所知 —— 被删元素下一轮复活，用户手绘的线又被 LLM
     * 重复画一条，拖走的线又被绑回原处（用户实测）。
     *
     * @param sessionId 会话 ID
     * @param request   画布现状: positions / removedIds /
     *                  userElements / unboundArrows，四者都可为空
     */
    @Operation(summary = "画布现状同步",
            description = "用户拖拽 / 删除 / 手绘之后，"
                    + "把画布现状写入后端 model："
                    + "坐标 + 被删元素 + 用户手绘元素")
    @PutMapping("/api/model/canvas")
    public Result<Void> syncCanvas(
            @RequestParam("sessionId") String sessionId,
            @RequestBody CanvasSyncRequest request) {
        sessionStore.updatePositions(
                sessionId, request.positions());
        sessionStore.removeElements(
                sessionId, request.removedIds());
        sessionStore.setUserElements(
                sessionId, request.userElements());
        // 拖动一条线会让 Excalidraw 解除它的端点绑定，必须回写，
        // 否则下一轮渲染又把它绑回原处（用户看到"拖了没反应"）
        sessionStore.releaseBindings(
                sessionId, request.unboundArrows());
        return Result.success();
    }
}
