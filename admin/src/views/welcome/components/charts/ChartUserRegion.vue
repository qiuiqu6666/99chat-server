<script setup lang="ts">
import { useDark, useECharts } from "@pureadmin/utils";
import { type PropType, ref, computed, watch, nextTick } from "vue";

const props = defineProps({
  rows: {
    type: Array as PropType<Array<{ name: string; value: number }>>,
    default: () => []
  }
});

const { isDark } = useDark();
const theme = computed(() => (isDark.value ? "dark" : "light"));
const chartRef = ref();
const { setOptions } = useECharts(chartRef, { theme });

watch(
  () => [props.rows, isDark.value],
  async () => {
    await nextTick();
    const sorted = [...props.rows].sort((a, b) => a.value - b.value);
    const names = sorted.map(r => r.name);
    const vals = sorted.map(r => r.value);
    setOptions({
      container: ".welcome-region-card",
      color: ["#41b6ff"],
      tooltip: {
        trigger: "axis",
        axisPointer: { type: "shadow" }
      },
      grid: {
        left: "4px",
        right: "24px",
        top: "16px",
        bottom: "16px",
        containLabel: true
      },
      xAxis: {
        type: "value",
        axisLabel: { fontSize: 12 },
        splitLine: {
          show: true,
          lineStyle: { opacity: isDark.value ? 0.15 : 0.45 }
        }
      },
      yAxis: {
        type: "category",
        data: names,
        axisLabel: { fontSize: 12 }
      },
      series: [
        {
          name: "用户数",
          type: "bar",
          barWidth: "55%",
          label: {
            show: true,
            position: "right",
            fontSize: 11
          },
          itemStyle: {
            color: "#41b6ff",
            borderRadius: [0, 6, 6, 0]
          },
          data: vals
        }
      ]
    });
  },
  { deep: true, immediate: true }
);
</script>

<template>
  <div ref="chartRef" style="width: 100%; height: 360px" />
</template>
