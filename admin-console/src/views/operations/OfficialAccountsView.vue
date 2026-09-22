<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  broadcastOfficial,
  createOfficialAccount,
  deleteOfficialAccount,
  linkOfficialAccount,
  listOfficialAccounts,
  updateOfficialAccount
} from "@/api/officialAccounts";
import { str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const createVisible = ref(false);
const linkVisible = ref(false);
const editVisible = ref(false);
const broadcastVisible = ref(false);
const saving = ref(false);

const createForm = reactive({
  slug: "",
  name: "",
  introduction: "",
  face_url: "",
  organization: "",
  owner_user_id: "",
  max_subscriber_num: undefined as number | undefined,
  sort_order: 0
});

const linkForm = reactive({
  official_account_id: "",
  slug: "",
  name: "",
  introduction: "",
  face_url: "",
  organization: "",
  owner_user_id: "",
  sort_order: 0
});

const editForm = reactive({
  official_account_id: "",
  name: "",
  introduction: "",
  face_url: "",
  organization: "",
  max_subscriber_num: undefined as number | undefined,
  enabled: true,
  sort_order: 0
});

const broadcastForm = reactive({
  official_account_id: "",
  name: "",
  text: ""
});

function asList(raw: unknown): Record<string, unknown>[] {
  if (Array.isArray(raw)) return raw as Record<string, unknown>[];
  const obj = (raw || {}) as Record<string, unknown>;
  if (Array.isArray(obj.data)) return obj.data as Record<string, unknown>[];
  if (Array.isArray(obj.items)) return obj.items as Record<string, unknown>[];
  return [];
}

async function load() {
  loading.value = true;
  try {
    rows.value = asList(await listOfficialAccounts());
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

function resetCreate() {
  Object.assign(createForm, {
    slug: "",
    name: "",
    introduction: "",
    face_url: "",
    organization: "",
    owner_user_id: "",
    max_subscriber_num: undefined,
    sort_order: 0
  });
}

async function submitCreate() {
  saving.value = true;
  try {
    await createOfficialAccount({ ...createForm });
    ElMessage.success("已创建");
    createVisible.value = false;
    resetCreate();
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

async function submitLink() {
  saving.value = true;
  try {
    await linkOfficialAccount({ ...linkForm });
    ElMessage.success("已绑定");
    linkVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

function openEdit(row: Record<string, unknown>) {
  editForm.official_account_id = str(row, "official_account_id", "officialAccountId");
  editForm.name = String(row.name || "");
  editForm.introduction = String(row.introduction || "");
  editForm.face_url = str(row, "face_url", "faceUrl");
  editForm.organization = String(row.organization || "");
  editForm.max_subscriber_num = Number(row.max_subscriber_num ?? row.maxSubscriberNum ?? 0) || undefined;
  editForm.enabled = Boolean(row.enabled);
  editForm.sort_order = Number(row.sort_order ?? row.sortOrder ?? 0);
  editVisible.value = true;
}

async function submitEdit() {
  saving.value = true;
  try {
    await updateOfficialAccount(editForm.official_account_id, {
      name: editForm.name,
      introduction: editForm.introduction,
      face_url: editForm.face_url,
      organization: editForm.organization,
      max_subscriber_num: editForm.max_subscriber_num,
      enabled: editForm.enabled,
      sort_order: editForm.sort_order
    });
    ElMessage.success("已保存");
    editVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

function openBroadcast(row: Record<string, unknown>) {
  broadcastForm.official_account_id = str(row, "official_account_id", "officialAccountId");
  broadcastForm.name = String(row.name || "");
  broadcastForm.text = "";
  broadcastVisible.value = true;
}

async function submitBroadcast() {
  saving.value = true;
  try {
    await broadcastOfficial(broadcastForm.official_account_id, { text: broadcastForm.text });
    ElMessage.success("广播已发送");
    broadcastVisible.value = false;
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

async function remove(row: Record<string, unknown>) {
  const id = str(row, "official_account_id", "officialAccountId");
  if (!id) return;
  try {
    await ElMessageBox.confirm(`确认删除公众号「${row.name || id}」？`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteOfficialAccount(id);
    ElMessage.success("已删除");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="公众号" />
    <div class="page-card">
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canWrite" type="primary" @click="createVisible = true; resetCreate()">创建</el-button>
        <el-button v-if="canWrite" @click="linkVisible = true">绑定已有</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="Slug" min-width="120">
          <template #default="{ row }">{{ row.slug || "—" }}</template>
        </el-table-column>
        <el-table-column label="名称" min-width="140">
          <template #default="{ row }">{{ row.name || "—" }}</template>
        </el-table-column>
        <el-table-column label="账号 ID" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "official_account_id", "officialAccountId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="简介" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.introduction || "—" }}</template>
        </el-table-column>
        <el-table-column label="订阅数" width="90">
          <template #default="{ row }">{{ row.subscriber_num ?? row.subscriberNum ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" width="70">
          <template #default="{ row }">{{ row.sort_order ?? row.sortOrder ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.create_time || row.createTime) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="primary" link @click="openBroadcast(row)">广播</el-button>
            <el-button type="danger" link @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="创建公众号" width="560px">
      <el-form label-width="110px">
        <el-form-item label="Slug" required><el-input v-model="createForm.slug" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="createForm.name" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="createForm.introduction" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="createForm.face_url" /></el-form-item>
        <el-form-item label="组织"><el-input v-model="createForm.organization" /></el-form-item>
        <el-form-item label="归属用户"><el-input v-model="createForm.owner_user_id" /></el-form-item>
        <el-form-item label="订阅上限"><el-input-number v-model="createForm.max_subscriber_num" :controls="false" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="createForm.sort_order" :controls="false" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="linkVisible" title="绑定已有公众号" width="560px">
      <el-form label-width="120px">
        <el-form-item label="账号 ID" required><el-input v-model="linkForm.official_account_id" /></el-form-item>
        <el-form-item label="Slug"><el-input v-model="linkForm.slug" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="linkForm.name" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="linkForm.introduction" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="linkForm.face_url" /></el-form-item>
        <el-form-item label="组织"><el-input v-model="linkForm.organization" /></el-form-item>
        <el-form-item label="归属用户"><el-input v-model="linkForm.owner_user_id" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="linkForm.sort_order" :controls="false" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitLink">绑定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑公众号" width="560px">
      <el-form label-width="110px">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="editForm.introduction" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="editForm.face_url" /></el-form-item>
        <el-form-item label="组织"><el-input v-model="editForm.organization" /></el-form-item>
        <el-form-item label="订阅上限"><el-input-number v-model="editForm.max_subscriber_num" :controls="false" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="editForm.sort_order" :controls="false" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="broadcastVisible" title="广播消息" width="520px">
      <p style="margin-bottom: 8px">公众号：{{ broadcastForm.name }}</p>
      <el-input v-model="broadcastForm.text" type="textarea" :rows="5" placeholder="广播文本" />
      <template #footer>
        <el-button @click="broadcastVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitBroadcast">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>
