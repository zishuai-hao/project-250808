(() => {
    const element = name => document.getElementById(`history-${name}`);
    const dialog = element("dialog"), ranges = element("ranges"), plot = element("plot");
    const custom = element("custom"), start = element("start"), end = element("end");
    const rangeError = element("range-error");
    const chartPanel = element("chart"), status = element("status");
    const details = element("tooltip"), refresh = element("refresh");
    const timeFormat = new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23",
    });
    const fullTimeFormat = new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
    });
    let deviceCode = null, data = null, pending = null, chart = null, pointIndex = 0;
    let rangeMode = "24";

    function beijingInput(time) {
        return new Date(time + 8 * 3600000).toISOString().slice(0, 16);
    }

    function queryParameters() {
        rangeError.hidden = true;
        if (rangeMode !== "custom") return new URLSearchParams({hours: rangeMode});
        if (!custom.reportValidity()) return null;
        const from = Date.parse(`${start.value}+08:00`), to = Date.parse(`${end.value}+08:00`);
        if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to || to - from > 45 * 86400000) {
            rangeError.textContent = "开始时间须早于结束时间，查询跨度不能超过 45 天。";
            rangeError.hidden = false;
            return null;
        }
        return new URLSearchParams({start: new Date(from).toISOString(), end: new Date(to).toISOString()});
    }

    function formatNumber(value, series) {
        return Number.isFinite(value) ? `${value.toFixed(series.precision)} ${series.unit}` : "缺失";
    }

    function deliveryText(point) {
        const delivery = point.delivery;
        if (!delivery) return "发送状态未知";
        const labels = {PENDING: "未发送", SENDING: "发送中", SENT: "已提交 MQTT", FAILED: "发送失败，待补发"};
        return (labels[delivery.status] || "发送状态未知")
            + (delivery.reportTime ? ` · 所属整点 ${delivery.reportTime}` : "")
            + (delivery.attempts ? ` · 已尝试 ${delivery.attempts} 次` : "");
    }

    function describePoint(index) {
        pointIndex = index;
        const point = data.points[index];
        details.textContent = `${fullTimeFormat.format(point.time)}　` + data.series
            .map((series, i) => `${series.name}：${formatNumber(point.values[i], series)}`).join("　")
            + `　${deliveryText(point)}`;
    }

    function draw() {
        const validPoints = data.points.filter(point => point.values.some(Number.isFinite)).length;
        const hasGaps = data.points.some(point => point.values.some(value => !Number.isFinite(value)));
        chartPanel.hidden = !validPoints;
        status.className = "muted";
        const windowText = `${timeFormat.format(data.fromTime)} — ${timeFormat.format(data.toTime)}`;
        if (!validPoints) {
            status.textContent = `${windowText}：该时间范围内暂无可用观测数据，可切换时间范围。`;
            return;
        }
        if (!window.echarts) throw new Error("图表组件未加载，请刷新页面");
        status.textContent = `${windowText} · ${validPoints} 个有效采集时间点`
            + (data.truncated ? " · 记录较多，仅显示最新 2000 条，请缩小时间范围查看。" : "")
            + (hasGaps ? " · 缺失值显示为断点。" : "");
        const units = [...new Set(data.series.map(series => series.unit))];
        chart = chart || echarts.init(plot, null, {renderer: "canvas"});
        chart.resize();
        chart.setOption({
            animation: false,
            useUTC: true,
            color: ["#0f766e", "#7c3aed", "#b45309"],
            aria: {enabled: true, label: {description: `${deviceCode} 的真实采集观测曲线，可使用左右方向键读取数值。`}},
            legend: {type: "scroll", top: 4, data: data.series.map(series => series.name)},
            grid: {left: 16, right: 16, top: 64, bottom: 80, containLabel: true},
            tooltip: {
                trigger: "axis", confine: true, renderMode: "richText",
                axisPointer: {type: "line", snap: true},
                formatter: items => {
                    if (!items.length) return "";
                    const index = items[0].dataIndex;
                    describePoint(index);
                    return fullTimeFormat.format(data.points[index].time) + "\n" + items.map(item => {
                        const series = data.series[item.seriesIndex];
                        return `${series.name}：${formatNumber(item.value[1], series)}`;
                    }).join("\n") + "\n" + deliveryText(data.points[index]);
                },
            },
            xAxis: {
                type: "time",
                // 单点也提供完整时间轴；多点以实际采集时间分布，缺测时段不会压缩成等间距。
                min: data.points.length === 1 ? data.fromTime : undefined,
                max: data.points.length === 1 ? data.toTime : undefined,
                axisLabel: {hideOverlap: true, formatter: value => timeFormat.format(value)},
            },
            yAxis: units.map((unit, index) => ({
                type: "value", name: unit, scale: true, position: index === 0 ? "left" : "right",
                splitLine: {show: index === 0, lineStyle: {color: "#e2e8f0"}},
                axisLabel: {formatter: value => Number(value.toPrecision(8)).toString()},
            })),
            dataZoom: [
                {type: "inside", xAxisIndex: 0, filterMode: "none"},
                {type: "slider", xAxisIndex: 0, bottom: 8, height: 24, filterMode: "none",
                    labelFormatter: value => timeFormat.format(value)},
            ],
            series: data.series.map((series, i) => ({
                name: series.name, type: "line", yAxisIndex: units.indexOf(series.unit),
                smooth: false, connectNulls: false, showSymbol: true, symbolSize: 5,
                lineStyle: {width: 2},
                data: data.points.map(point => [point.time, point.values[i]]),
            })),
        }, {notMerge: true});
        describePoint(data.points.length - 1);
    }

    async function load() {
        const query = queryParameters();
        if (!query) return;
        pending?.abort();
        const request = new AbortController();
        pending = request;
        data = null;
        chart?.clear();
        chartPanel.hidden = true;
        details.textContent = "";
        status.className = "muted";
        status.textContent = "正在加载观测数据…";
        refresh.disabled = true;
        // 关闭弹窗、切换设备或时间范围时取消旧请求，避免旧曲线覆盖新选择。
        const timeout = window.setTimeout(() => request.abort(), 15000);
        try {
            const response = await fetch(`/api/device-history/${encodeURIComponent(deviceCode)}?${query}`,
                {cache: "no-store", signal: request.signal});
            if (!response.ok) throw new Error(response.status === 404 ? "设备不存在" : `HTTP ${response.status}`);
            const result = await response.json();
            if (pending !== request || !dialog.open) return;
            data = result;
            draw();
        } catch (error) {
            if (pending !== request || !dialog.open) return;
            data = null;
            chartPanel.hidden = true;
            status.className = "error";
            status.textContent = `曲线加载失败：${error.name === "AbortError" ? "请求超时" : error.message}，请点击“刷新曲线”重试。`;
        } finally {
            window.clearTimeout(timeout);
            if (pending === request) {
                pending = null;
                refresh.disabled = false;
            }
        }
    }

    document.getElementById("device-rows").addEventListener("click", event => {
        const button = event.target.closest("[data-history-device]");
        if (!button) return;
        deviceCode = button.dataset.historyDevice;
        element("device").textContent = deviceCode;
        dialog.showModal();
        load();
    });
    element("close").addEventListener("click", () => dialog.close());
    dialog.addEventListener("close", () => {
        pending?.abort();
        pending = null;
        data = null;
        chart?.dispose();
        chart = null;
    });
    plot.addEventListener("keydown", event => {
        if (!data || !chart || !data.points.length || !["ArrowLeft", "ArrowRight"].includes(event.key)) return;
        event.preventDefault();
        const index = Math.max(0, Math.min(data.points.length - 1, pointIndex + (event.key === "ArrowLeft" ? -1 : 1)));
        describePoint(index);
        chart.dispatchAction({type: "showTip", seriesIndex: 0, dataIndex: index});
    });
    ranges.addEventListener("click", event => {
        const button = event.target.closest("[data-hours]");
        if (!button) return;
        const previousHours = rangeMode === "custom" ? 24 : Number(rangeMode);
        rangeMode = button.dataset.hours;
        ranges.querySelectorAll("button").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
        custom.hidden = rangeMode !== "custom";
        rangeError.hidden = true;
        if (rangeMode !== "custom") {
            load();
            return;
        }
        if (!start.value || !end.value) {
            const now = Date.now();
            start.value = beijingInput(data?.fromTime ?? now - previousHours * 3600000);
            end.value = beijingInput(data?.toTime ?? now);
        }
        pending?.abort();
        pending = null;
        data = null;
        chart?.clear();
        chartPanel.hidden = true;
        refresh.disabled = false;
        status.className = "muted";
        status.textContent = "设置北京时间的起止时间后，点击“查询”查看曲线。";
        start.focus();
    });
    custom.addEventListener("submit", event => {
        event.preventDefault();
        load();
    });
    refresh.addEventListener("click", load);
    new ResizeObserver(() => { if (dialog.open && chart) chart.resize(); }).observe(plot);
})();
