import { onMounted, reactive, ref } from "vue";
import { errMessage } from "@/utils/format";
import { ElMessage } from "element-plus";

export function usePagedList(
  fetcher: (params: Record<string, unknown>) => Promise<unknown>,
  extraParams: () => Record<string, unknown> = () => ({})
) {
  const loading = ref(false);
  const rows = ref<Record<string, unknown>[]>([]);
  const total = ref(0);
  const query = reactive({
    page: 1,
    page_size: 20,
    keyword: ""
  });

  function pickItems(raw: unknown): { items: Record<string, unknown>[]; total: number } {
    const obj = (raw || {}) as Record<string, unknown>;
    const data =
      obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)
        ? (obj.data as Record<string, unknown>)
        : obj;
    const items = (data.items || data.list || data.rows || data.content || []) as Record<
      string,
      unknown
    >[];
    const t = Number(data.total ?? data.count ?? items.length ?? 0);
    return { items: Array.isArray(items) ? items : [], total: t };
  }

  async function load() {
    loading.value = true;
    try {
      const raw = await fetcher({
        page: query.page,
        page_size: query.page_size,
        keyword: query.keyword || undefined,
        ...extraParams()
      });
      const picked = pickItems(raw);
      rows.value = picked.items;
      total.value = picked.total;
    } catch (e) {
      rows.value = [];
      total.value = 0;
      ElMessage.warning(errMessage(e));
    } finally {
      loading.value = false;
    }
  }

  onMounted(load);
  return { loading, rows, total, query, load };
}
