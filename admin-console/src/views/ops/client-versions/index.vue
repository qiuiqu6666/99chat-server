<script setup lang="ts">
import { computed, h, reactive, ref } from "vue";
import { NButton } from "naive-ui";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { createClientVersion, deleteClientVersion, listClientVersions, updateClientVersion } from "@/service/api/clientVersions";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { buildAdminColumns } from "@/utils/adminTable";

defineOptions({ name: "ops_client-versions" });

const platformOptions = [
  { label: "安卓", value: "android" },
  { label: "苹果", value: "ios" },
  { label: "电脑", value: "web" }
];
const updateOptions = [
  { label: "不提示", value: "none" },
  { label: "可选更新", value: "optional" },
  { label: "强制更新", value: "force" },
  { label: "灰度", value: "gray" }
];
const enabledOptions = [
  { label: "全部", value: "" },
  { label: "启用", value: "true" },
  { label: "停用", value: "false" }
];

const filters = reactive({ platform: null as string | null, enabled: "" });
const { loading, rows, total, query, load } = usePagedList(
  params => listClientVersions(params),
  () => ({
    platform: filters.platform || undefined,
    enabled: filters.enabled || undefined
  })
);

const columns = computed(() =>
  buildAdminColumns(rows.value, [
    {
      title: "操作",
      key: "op",
      width: 120,
      fixed: "right",
      render: row =>
        h("div", { class: "flex gap-8px" }, [
          h(
            NButton,
            { text: true, type: "primary", size: "small", disabled: !hasPerm("user.write"), onClick: () => openEdit(row) },
            { default: () => "编辑" }
          ),
          h(
            NButton,
            { text: true, type: "error", size: "small", disabled: !hasPerm("user.write"), onClick: () => remove(row) },
            { default: () => "删除" }
          )
        ])
    }
  ])
);

const dialog = ref(false);
const saving = ref(false);
const editingId = ref<string | number | null>(null);
const form = reactive({
  platform: "android",
  version: "",
  version_code: null as number | null,
  min_version: "",
  min_version_code: null as number | null,
  update_type: "optional",
  download_url: "",
  changelog: "",
  enabled: true,
  gray_percent: 100,
  published_at: null as number | null
});

function resetForm() {
  form.platform = "android";
  form.version = "";
  form.version_code = null;
  form.min_version = "";
  form.min_version_code = null;
  form.update_type = "optional";
  form.download_url = "";
  form.changelog = "";
  form.enabled = true;
  form.gray_percent = 100;
  form.published_at = null;
}

function asNumber(value: unknown) {
  if (value == null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function openCreate() {
  editingId.value = null;
  resetForm();
  dialog.value = true;
}

function openEdit(row: Record<string, unknown>) {
  editingId.value = row.id as string | number;
  form.platform = String(row.platform || "android");
  form.version = String(row.version || "");
  form.version_code = asNumber(row.version_code);
  form.min_version = String(row.min_version || "");
  form.min_version_code = asNumber(row.min_version_code);
  form.update_type = String(row.update_type || "optional");
  form.download_url = String(row.download_url || "");
  form.changelog = String(row.changelog || "");
  form.enabled = row.enabled !== false;
  form.gray_percent = asNumber(row.gray_percent) ?? 100;
  const published = row.published_at ? Date.parse(String(row.published_at)) : Number.NaN;
  form.published_at = Number.isNaN(published) ? null : published;
  dialog.value = true;
}

function payload() {
  const body: Record<string, unknown> = {
    platform: form.platform,
    version: form.version.trim(),
    version_code: form.version_code,
    min_version: form.min_version.trim(),
    min_version_code: form.min_version_code,
    update_type: form.update_type,
    download_url: form.download_url.trim(),
    changelog: form.changelog.trim(),
    enabled: form.enabled,
    gray_percent: form.gray_percent
  };
  if (form.published_at) body.published_at = new Date(form.published_at).toISOString();
  return body;
}

async function submit() {
  if (!hasPerm("user.write")) return;
  if (!form.platform || !form.version.trim()) {
    window.$message?.warning("请填写平台和版本号");
    return;
  }
  saving.value = true;
  try {
    if (editingId.value == null) await createClientVersion(payload());
    else await updateClientVersion(editingId.value, payload());
    dialog.value = false;
    window.$message?.success("已保存");
    load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

function remove(row: Record<string, unknown>) {
  if (!hasPerm("user.write")) return;
  window.$dialog?.warning({
    title: "删除版本",
    content: `确认删除 ${String(row.version || "")}？`,
    positiveText: "删除",
    negativeText: "取消",
    onPositiveClick: async () => {
      try {
        await deleteClientVersion(row.id as string | number);
        window.$message?.success("已删除");
        load();
      } catch (e) {
        window.$message?.error(errMessage(e));
      }
    }
  });
}

function search() {
  query.page = 1;
  load();
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="版本发布" subtitle="客户端版本、下载地址和更新策略">
      <NButton v-if="hasPerm('user.write')" type="primary" @click="openCreate">新建版本</NButton>
    </PageHeader>
    <NSpace class="mb-12px" align="center">
      <NSelect v-model:value="filters.platform" clearable placeholder="平台" :options="platformOptions" style="width: 140px" />
      <NInput v-model:value="query.keyword" clearable placeholder="版本号 / 说明 / 下载地址" style="width: 240px" @keyup.enter="search" />
      <NSelect v-model:value="filters.enabled" :options="enabledOptions" style="width: 120px" />
      <NButton type="primary" @click="search">查询</NButton>
    </NSpace>
    <NDataTable
      remote
      bordered
      striped
      :single-line="false"
      size="small"
      :scroll-x="1600"
      :loading="loading"
      :data="rows"
      :columns="columns"
      :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }"
    />
    <NModal v-model:show="dialog" preset="card" :title="editingId == null ? '新建版本' : '编辑版本'" style="width: 720px">
      <NForm label-placement="left" label-width="96">
        <NGrid :cols="2" :x-gap="16">
          <NFormItemGi label="平台">
            <NSelect v-model:value="form.platform" :options="platformOptions" />
          </NFormItemGi>
          <NFormItemGi label="版本号">
            <NInput v-model:value="form.version" placeholder="例如 1.2.0" />
          </NFormItemGi>
          <NFormItemGi label="构建号">
            <NInputNumber v-model:value="form.version_code" class="w-full" :min="0" :show-button="false" placeholder="versionCode" />
          </NFormItemGi>
          <NFormItemGi label="更新类型">
            <NSelect v-model:value="form.update_type" :options="updateOptions" />
          </NFormItemGi>
          <NFormItemGi label="最低版本">
            <NInput v-model:value="form.min_version" placeholder="低于此版本需更新" />
          </NFormItemGi>
          <NFormItemGi label="最低构建号">
            <NInputNumber v-model:value="form.min_version_code" class="w-full" :min="0" :show-button="false" />
          </NFormItemGi>
          <NFormItemGi label="灰度比例">
            <NInputNumber v-model:value="form.gray_percent" class="w-full" :min="0" :max="100" />
          </NFormItemGi>
          <NFormItemGi label="启用">
            <NSwitch v-model:value="form.enabled" />
          </NFormItemGi>
          <NFormItemGi label="发布时间" :span="2">
            <NDatePicker v-model:value="form.published_at" type="datetime" clearable class="w-full" />
          </NFormItemGi>
          <NFormItemGi label="下载地址" :span="2">
            <NInput v-model:value="form.download_url" placeholder="安装包或商店地址" />
          </NFormItemGi>
          <NFormItemGi label="更新说明" :span="2">
            <NInput v-model:value="form.changelog" type="textarea" :rows="4" placeholder="客户端更新弹窗里的说明" />
          </NFormItemGi>
        </NGrid>
      </NForm>
      <NSpace justify="end">
        <NButton @click="dialog = false">取消</NButton>
        <NButton type="primary" :loading="saving" @click="submit">保存</NButton>
      </NSpace>
    </NModal>
  </div>
</template>
