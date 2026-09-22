import dayjs from "dayjs";
import { ref, reactive, onMounted, type Ref } from "vue";
import { useRouter } from "vue-router";
import { message } from "@/utils/message";
import { deviceDetection } from "@pureadmin/utils";
import type { PaginationProps } from "@pureadmin/table";
import { getGroupsList, mapGroupItemToRow } from "@/api/im-group";
import { adminApiErrMessage } from "@/api/im-user";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

export type GroupRowCtx = {
  gId: string;
  groupNo: string;
  name: string;
};

export function useGroupList(tableRef: Ref) {
  const router = useRouter();
  const form = reactive({
    name: "",
    groupNo: "",
    owner: "",
    status: "" as string
  });

  const loading = ref(true);
  const dataList = ref<any[]>([]);
  const imTotal = ref<number | null>(null);
  const listTruncated = ref(false);
  const pagination = reactive<PaginationProps>({
    total: 0,
    pageSize: 10,
    currentPage: 1,
    background: true
  });

  const columns: TableColumnList = [
    { label: "群 ID", prop: "groupNo", minWidth: 110 },
    { label: "群名称", prop: "name", minWidth: 160, showOverflowTooltip: true },
    { label: "群主 IM 号", prop: "ownerUid", minWidth: 118 },
    {
      label: "创建人昵称",
      prop: "createUserNickname",
      minWidth: 110,
      formatter: ({
        createUserNickname
      }: {
        createUserNickname?: string;
      }) =>
        createUserNickname && createUserNickname.trim() !== ""
          ? createUserNickname
          : "—"
    },
    {
      label: "成员数",
      prop: "memberCount",
      width: 100,
      formatter: ({ memberCount, memberLimit }) =>
        `${memberCount ?? 0} / ${memberLimit ?? "—"}`
    },
    {
      label: "群状态",
      prop: "_status",
      width: 110,
      cellRenderer: ({ row, props }) => {
        const m = row.statusDisplay ?? {
          label: "未知",
          type: "info" as const
        };
        return (
          <el-tag size={props.size} type={m.type} effect="plain">
            {m.label}
          </el-tag>
        );
      }
    },
    {
      label: "创建时间",
      prop: "createTime",
      minWidth: 166,
      formatter: ({ createTime }: { createTime?: string | number }) => {
        if (createTime == null || createTime === "") return "—";
        if (typeof createTime === "number")
          return dayjs(createTime).format("YYYY-MM-DD HH:mm:ss");
        const d = dayjs(createTime);
        return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : String(createTime);
      }
    },
    { label: "操作", fixed: "right", width: 88, slot: "operation" }
  ];

  async function onSearch() {
    loading.value = true;
    try {
      const keyword =
        [form.name.trim(), form.groupNo.trim()].filter(Boolean).join(" ").trim() ||
        undefined;

      const res = await getGroupsList({
        page: pagination.currentPage,
        page_size: pagination.pageSize,
        keyword,
        g_status: form.status === "" ? undefined : form.status,
        sort: "create_time_desc"
      });

      dataList.value = (res.items ?? []).map(it =>
        mapGroupItemToRow(it as Record<string, unknown>)
      );
      pagination.total = res.total ?? 0;
      imTotal.value =
        typeof res.im_total === "number" && res.im_total > 0 ? res.im_total : null;
      listTruncated.value = res.truncated === true;
      if (
        typeof res.page_size === "number" &&
        res.page_size > 0 &&
        res.page_size !== pagination.pageSize
      ) {
        pagination.pageSize = res.page_size;
      }
    } catch (err: unknown) {
      dataList.value = [];
      pagination.total = 0;
      imTotal.value = null;
      listTruncated.value = false;
      const ax = err as { response?: { status?: number; data?: { error?: string } } };
      if (
        ax?.response?.status === 403 ||
        ax?.response?.data?.error === "forbidden"
      ) {
        message("无群组列表权限（需要 group.read）", { type: "warning" });
      } else if (ax?.response?.status !== 401) {
        message(adminApiErrMessage(err, "群组列表加载失败"), {
          type: "warning"
        });
      }
    } finally {
      loading.value = false;
      tableRef.value?.setAdaptive?.();
    }
  }

  function resetForm(formEl?: any) {
    formEl?.resetFields?.();
    form.name = "";
    form.groupNo = "";
    form.owner = "";
    form.status = "";
    pagination.currentPage = 1;
    onSearch();
  }

  function handleSizeChange(val: number) {
    pagination.pageSize = val;
    pagination.currentPage = 1;
    onSearch();
  }

  function handleCurrentChange(val: number) {
    pagination.currentPage = val;
    onSearch();
  }

  function handleDetail(row: any) {
    router.push({
      name: "ImGroupDetail",
      params: { gId: String(row.gId ?? row.groupNo ?? "") }
    });
  }

  useAdminRealtimeInvalidate(
    [
      ADMIN_REALTIME_EVENTS.GROUP_CREATED,
      ADMIN_REALTIME_EVENTS.GROUP_UPDATED,
      ADMIN_REALTIME_EVENTS.GROUP_STATUS_CHANGED,
      ADMIN_REALTIME_EVENTS.GROUP_MEMBER_UPDATED
    ],
    () => onSearch(),
    { debounceMs: 600 }
  );

  onMounted(onSearch);

  return {
    form,
    loading,
    columns,
    dataList,
    imTotal,
    listTruncated,
    pagination,
    deviceDetection,
    onSearch,
    resetForm,
    handleSizeChange,
    handleCurrentChange,
    handleDetail
  };
}
