import { reactive } from "vue";
import type { PaginationProps } from "@pureadmin/table";
import Empty from "./empty.svg?component";

export const welcomeDailyColumns: TableColumnList = [
  {
    sortable: true,
    label: "序号",
    prop: "id",
    width: 76
  },
  {
    sortable: true,
    label: "统计日期",
    prop: "date",
    minWidth: 126
  },
  {
    sortable: true,
    label: "新注册用户数",
    prop: "newRegistrations",
    minWidth: 132,
    formatter: ({ newRegistrations }: { newRegistrations: number }) =>
      typeof newRegistrations === "number"
        ? String(newRegistrations)
        : "—"
  },
  {
    sortable: true,
    label: "当日登录（去重）",
    prop: "avgOnline",
    minWidth: 154,
    formatter: ({
      avgOnline
    }: {
      avgOnline: number | null | undefined;
    }) =>
      avgOnline != null && Number.isFinite(Number(avgOnline))
        ? Number(avgOnline).toLocaleString("zh-CN")
        : "—"
  }
];

export function createWelcomeDailyPagination(
  total: number
): PaginationProps {
  return reactive({
    pageSize: 10,
    currentPage: 1,
    layout: "prev, pager, next, total",
    total,
    align: "center"
  }) as PaginationProps;
}

export { Empty };
