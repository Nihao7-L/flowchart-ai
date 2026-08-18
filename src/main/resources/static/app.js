/**
 * FlowAI 前端逻辑（Vue 3 CDN 版）
 *
 * Vue 3 核心概念速查：
 * - ref()          把普通变量变成"响应式"——改了值页面自动更新
 * - computed()     计算属性——依赖的 ref 变了它自动重算
 * - v-model        双向绑定——输入框 ↔ 变量同步
 * - v-if / v-html  条件渲染 / 把字符串当 HTML 插入
 * - @event         事件绑定（@click, @wheel, @mousedown...）
 * - :style         动态绑定样式
 */
const { createApp, ref, computed, onMounted } = Vue;

createApp({
    setup() {
        // ===== 响应式数据 =====
        const textInput = ref('');           // 输入框内容
        const loading = ref(false);          // 生成中？
        const svgContent = ref('');          // 后端返回的 SVG 字符串
        const plantUmlSource = ref('');      // 后端返回的 PlantUML 源码（下载用）
        const errorMessage = ref('');        // 错误信息
        const theme = ref('light');          // 当前主题
        const chartType = ref('flowchart');  // 图表类型（只有 flowchart 能用）
        const selectedModel = ref('kimi');   // 模型选择

        // Toast 状态
        const toastMsg = ref('');
        const toastVisible = ref(false);
        let toastTimer = null;

        // SVG 缩放 / 拖拽状态
        const scale = ref(1);
        const translateX = ref(0);
        const translateY = ref(0);
        const isDragging = ref(false);
        let dragStart = { x: 0, y: 0, tx: 0, ty: 0 };

        // ===== 计算属性 =====
        // hasResult：有 SVG 内容就显示画布，否则显示空状态
        const hasResult = computed(() => svgContent.value !== '');

        // 图表类型的中文名（按钮、空状态文案都用）
        const typeNames = { flowchart: '流程图', mindmap: '思维导图', architecture: '架构图' };

        // 按钮文字：点击"思维导图"后，按钮自动变成"生成思维导图"
        const buttonText = computed(() => '生成' + (typeNames[chartType.value] || '流程图'));

        // 输入框占位符：按图表类型给不同的示例
        const placeholderText = computed(() => {
            if (chartType.value === 'mindmap') {
                return '例如：电商系统的组成，包括前端、后端、数据库、基础设施';
            }
            if (chartType.value === 'architecture') {
                return '例如：电商系统架构，Web前端通过网关访问订单服务和用户服务，订单服务依赖数据库和消息队列';
            }
            return '例如：用户输入账号密码，系统验证，验证通过则进入首页，验证失败则提示错误并重新输入';
        });

        // 示例文案（键 = mini-tag 的名字）
        const examples = {
            '登录': '用户输入账号密码 → 系统验证 → 验证通过则进入首页，验证失败则提示错误并重新输入',
            '审批': '员工提交请假申请 → 主管审批 → 审批通过则通知员工并记录，审批驳回则通知员工修改重新提交',
            '电商': '电商系统组成：前端包括Web商城、小程序、App，后端包括订单服务、用户服务、支付服务，基础设施包括数据库、缓存、消息队列',
            '系统架构': '电商系统架构：Web前端通过HTTP访问API网关，网关路由到订单服务和用户服务，订单服务依赖MySQL数据库和Redis缓存，支付服务对接第三方支付系统'
        };

        // ===== 方法 =====

        /** 填入示例 */
        function fillExample(type) {
            textInput.value = examples[type] || '';
        }

        /** 切换图表类型：切换时清空旧结果，避免两种图混在一起 */
        function setChartType(type) {
            chartType.value = type;
            svgContent.value = '';
            plantUmlSource.value = '';
            errorMessage.value = '';
            resetZoom();
        }

        /** 显示 Toast 轻提示 */
        function showToast(msg) {
            toastMsg.value = msg;
            toastVisible.value = true;
            clearTimeout(toastTimer);
            toastTimer = setTimeout(() => { toastVisible.value = false; }, 2000);
        }

        /** 切换明暗主题 */
        function toggleTheme() {
            theme.value = theme.value === 'dark' ? 'light' : 'dark';
            document.body.setAttribute('data-theme', theme.value);
            localStorage.setItem('flowai-theme', theme.value);
        }

        /** 模型切换（占位：当前只有 Kimi 接入了） */
        function onModelChange() {
            if (selectedModel.value !== 'kimi') {
                showToast(selectedModel.value + ' 模型接入中，当前仅 Kimi 可用');
                selectedModel.value = 'kimi';
            }
        }

        /** 核心：发起生成请求 */
        async function generate() {
            const text = textInput.value.trim();

            // 边界检查
            if (!text) { errorMessage.value = '请输入流程描述'; return; }
            if (text.length > 2000) { errorMessage.value = '输入内容过长，请精简到 2000 字以内'; return; }

            // 进入 loading：清空旧内容
            loading.value = true;
            errorMessage.value = '';
            svgContent.value = '';
            resetZoom();

            // 70 秒超时（和后端 60 秒配套，前端比后端多留 10 秒余量）
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 70000);

            try {
                const res = await fetch('/api/generate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    // type 告诉后端要生成哪种图
                    body: JSON.stringify({ text, type: chartType.value }),
                    signal: controller.signal
                });
                clearTimeout(timeoutId);
                const result = await res.json();

                if (result.code === 200) {
                    svgContent.value = result.data.svg;
                    plantUmlSource.value = result.data.plantUml;
                } else {
                    errorMessage.value = result.message || '生成失败';
                }
            } catch (err) {
                console.error('请求失败:', err);
                if (err.name === 'AbortError') {
                    errorMessage.value = '请求超时（70秒），请检查网络或后端是否正常';
                } else {
                    errorMessage.value = '生成失败: ' + err.message;
                }
            } finally {
                loading.value = false;
            }
        }

        /** 下载（按需渲染） */
        async function downloadFile(format) {
            if (!plantUmlSource.value) return;
            try {
                const res = await fetch('/api/download', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ plantUml: plantUmlSource.value, format })
                });
                const blob = await res.blob();
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                // 文件名按图表类型区分：flowchart.svg / mindmap.png / architecture.png
                a.download = (typeNames[chartType.value] === '流程图' ? 'flowchart' : chartType.value) + '.' + format;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            } catch (err) {
                showToast('下载失败: ' + err.message);
            }
        }

        // ===== SVG 缩放 / 拖拽 =====

        /** 应用 transform 到 wrapper */
        function applyTransform() {
            // Vue 的 :style 绑定会自动响应 scale/translateX/translateY 的变化
            // 所以这里不需要手动操作 DOM，只要改 ref 值就行
        }

        function zoomIn() {
            scale.value = Math.min(scale.value * 1.2, 5);
        }
        function zoomOut() {
            scale.value = Math.max(scale.value / 1.2, 0.2);
        }
        function resetZoom() {
            scale.value = 1;
            translateX.value = 0;
            translateY.value = 0;
        }

        /** 滚轮缩放 */
        function onWheel(e) {
            const delta = e.deltaY > 0 ? -0.1 : 0.1;
            scale.value = Math.max(0.2, Math.min(5, scale.value + delta));
        }

        /** 拖拽开始 */
        function onMouseDown(e) {
            isDragging.value = true;
            dragStart.x = e.clientX;
            dragStart.y = e.clientY;
            dragStart.tx = translateX.value;
            dragStart.ty = translateY.value;
        }

        /** 拖拽移动 */
        function onMouseMove(e) {
            if (!isDragging.value) return;
            translateX.value = dragStart.tx + (e.clientX - dragStart.x);
            translateY.value = dragStart.ty + (e.clientY - dragStart.y);
        }

        /** 拖拽结束 */
        function onMouseUp() {
            isDragging.value = false;
        }

        // ===== 生命周期：页面加载时恢复主题 =====
        onMounted(() => {
            const saved = localStorage.getItem('flowai-theme') || 'light';
            theme.value = saved;
            document.body.setAttribute('data-theme', saved);
        });

        // 把所有需要模板访问的数据和方法 return 出去
        return {
            textInput, loading, svgContent, plantUmlSource, errorMessage,
            theme, chartType, selectedModel,
            toastMsg, toastVisible,
            scale, translateX, translateY, isDragging,
            hasResult, buttonText, placeholderText,
            fillExample, showToast, toggleTheme, onModelChange, setChartType,
            generate, downloadFile,
            zoomIn, zoomOut, resetZoom,
            onWheel, onMouseDown, onMouseMove, onMouseUp
        };
    }
}).mount('#app');
