<script setup lang="ts">
import { useDark, useECharts } from "@pureadmin/utils";
import { type PropType, ref, computed, watch, nextTick } from "vue";

const props = defineProps({
  items: {
    type: Array as PropType<Array<{ name: string; value: number }>>,
    default: () => []
  }
});

const { isDark } = useDark();
const theme = computed(() => (isDark.value ? "dark" : "light"));
const chartRef = ref();
const { setOptions } = useECharts(chartRef, { theme });

const palette = ["#41b6ff", "#e85f33", "#26ce83", "#7846e5", "#909399"];

watch(
  () => [props.items, isDark.value],
  async () => {
    await nextTick();
    const data = props.items.map((it, i) => ({
      ...it,
      itemStyle: { color: palette[i % palette.length] }
    }));
    setOptions({
      container: ".welcome-device-card",
      tooltip: {
        trigger: "item",
        formatter: (p: any) => {
          const v = p.value;
          const name = p.name;
          const pct = p.percent ?? 0;
          return `${name}<br/>用户数：${v}<br/>占比：${pct}%`;
        }
      },
      legend: {
        bottom: 8,
        icon: "circle",
        textStyle: { fontSize: 12 }
      },
      series: [
        {
          name: "设备",
          type: "pie",
          radius: ["42%", "72%"],
          center: ["50%", "46%"],
          avoidLabelOverlap: true,
          itemStyle: {
            borderRadius: 6,
            borderColor: "var(--el-bg-color)",
            borderWidth: 2
          },
          label: {
            formatter: "{b}\n{d}%",
            fontSize: 11
          },
          data
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
