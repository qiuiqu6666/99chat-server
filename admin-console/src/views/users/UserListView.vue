<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserDetailView from "@/views/users/UserDetailView.vue";
import {
  createGenerationTask,
  createUser,
  listUsers,
  setLoginDisabled
} from "@/api/users";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const router = useRouter();
const canWrite = () => hasPerm("user.write");

const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  status: "",
  is_online: "",
  sort: "register_time_desc",
  game_privileged: "",
  skip_device_sms: ""
});

const createVisible = ref(false);
const genVisible = ref(false);
const detailOpen = ref(false);
const detailUid = ref("");
const createForm = reactive({ nickname: "", password: "", sex: "" });
const genForm = reactive({ password: "", count: 10, sex: "" });
const busy = ref(false);

function openDetail(uid: string) {
  if (!uid) return;
  detailUid.value = uid;
  detailOpen.value = true;
}

function uidOf(row: Record<string, unknown>) {
  return String(row.user_uid || row.userUid || "");
}

function isDisabled(row: Record<string, unknown>) {
  return Number(row.user_status ?? row.status) === 0;
}

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
    const raw = await listUsers({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      is_online: query.is_online || undefined,
      sort: query.sort || "register_time_desc",
      game_privileged: query.game_privileged || undefined,
      skip_device_sms: query.skip_device_sms || undefined
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

function search() {
  query.page = 1;
  load();
}

function resetFilters() {
  query.keyword = "";
  query.status = "";
  query.is_online = "";
  query.sort = "register_time_desc";
  query.game_privileged = "";
  query.skip_device_sms = "";
  query.page = 1;
  load();
}

function walletText(row: Record<string, unknown>) {
  const bal = row.wallet_balance ?? row.walletBalance;
  const frozen = row.wallet_frozen_amount ?? row.walletFrozenAmount;
  if (bal == null && frozen == null) return "—";
  const f = frozen != null && String(frozen) !== "0" ? ` / 冻 ${frozen}` : "";
  return `${bal ?? "0"}${f}`;
}

async function toggleDisabled(row: Record<string, unknown>) {
  if (!canWrite()) return;
  const uid = uidOf(row);
  const disabled = !isDisabled(row);
  try {
    await ElMessageBox.confirm(
      disabled ? `确认禁用用户 ${uid}？将禁止登录。` : `确认解禁用户 ${uid}？`,
      "确认",
      { type: "warning" }
    );
    await setLoginDisabled({
      user_uid: uid,
      disabled,
      clear_http_token: disabled
    });
    ElMessage.success(disabled ? "已禁用" : "已解禁");
    load();
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

async function submitCreate() {
  if (!createForm.nickname || !createForm.password) {
    ElMessage.warning("请填写昵称和密码");
    return;
  }
  busy.value = true;
  try {
    const r = (await createUser({
      nickname: createForm.nickname,
      password: createForm.password,
      sex: createForm.sex || undefined
    })) as Record<string, unknown>;
    const uid = String(r.user_uid || r.userUid || "");
    ElMessage.success(uid ? `已创建 ${uid}` : "已创建");
    createVisible.value = false;
    createForm.nickname = "";
    createForm.password = "";
    createForm.sex = "";
    load();
    if (uid) openDetail(uid);
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    busy.value = false;
  }
}

async function submitGen() {
  if (!genForm.password || genForm.count < 1) {
    ElMessage.warning("请填写密码与数量");
    return;
  }
  busy.value = true;
  try {
    await createGenerationTask({
      password: genForm.password,
      count: genForm.count,
      sex: genForm.sex || undefined
    });
    ElMessage.success("已创建生成任务");
    genVisible.value = false;
    router.push("/users/generation-tasks");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="用户列表" subtitle="查询、禁用与建号">
      <el-space v-if="canWrite()">
        <el-button type="primary" @click="createVisible = true">创建用户</el-button>
        <el-button @click="genVisible = true">批量生成任务</el-button>
      </el-space>
    </PageHeader>
    <div class="page-card">
      <div class="toolbar" style="flex-wrap: wrap; gap: 8px">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="UID / 手机 / 昵称"
          style="width: 200px"
          @keyup.enter="search"
        />
        <el-select
          v-model="query.status"
          clearable
          placeholder="账号状态"
          style="width: 120px"
          @change="search"
        >
          <el-option label="正常" value="1" />
          <el-option label="禁用" value="0" />
        </el-select>
        <el-select
          v-model="query.is_online"
          clearable
          placeholder="在线状态"
          style="width: 120px"
          @change="search"
        >
          <el-option label="在线" value="1" />
          <el-option label="离线" value="0" />
        </el-select>
        <el-select
          v-model="query.sort"
          placeholder="排序"
          style="width: 180px"
          @change="search"
        >
          <el-option label="最近上线优先" value="last_active_desc" />
          <el-option label="上线时间升序" value="last_active_asc" />
          <el-option label="注册时间降序" value="register_time_desc" />
          <el-option label="注册时间升序" value="register_time_asc" />
          <el-option label="昵称 A→Z" value="nickname_asc" />
          <el-option label="昵称 Z→A" value="nickname_desc" />
        </el-select>
        <el-select
          v-model="query.game_privileged"
          clearable
          placeholder="游戏特权"
          style="width: 120px"
        >
          <el-option label="有特权" value="1" />
          <el-option label="无特权" value="0" />
        </el-select>
        <el-select
          v-model="query.skip_device_sms"
          clearable
          placeholder="跳过设备短信"
          style="width: 140px"
        >
          <el-option label="已跳过" value="1" />
          <el-option label="未跳过" value="0" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="头像" width="64" fixed>
          <template #default="{ row }">
            <el-image
              v-if="row.user_avatar_file_name || row.userAvatarFileName"
              :src="String(row.user_avatar_file_name || row.userAvatarFileName)"
              style="width: 36px; height: 36px; border-radius: 6px"
              fit="cover"
              :preview-src-list="[String(row.user_avatar_file_name || row.userAvatarFileName)]"
              preview-teleported
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="110" fixed />
        <el-table-column label="用户 UID" min-width="150">
          <template #default="{ row }">
            <el-button v-if="uidOf(row)" link type="primary" @click="openDetail(uidOf(row))">
              {{ uidOf(row) }}
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="手机" min-width="120">
          <template #default="{ row }">{{ row.phone_num || row.phoneNum || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="isDisabled(row) ? 'danger' : 'success'" size="small">
              {{ isDisabled(row) ? "禁用" : "正常" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线" width="70">
          <template #default="{ row }">
            <el-tag
              :type="Number(row.is_online ?? row.isOnline) === 1 ? 'success' : 'info'"
              size="small"
            >
              {{ Number(row.is_online ?? row.isOnline) === 1 ? "在线" : "离线" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近上线" min-width="160">
          <template #default="{ row }">
            {{
              formatTime(
                row.last_active_time ||
                  row.lastActiveTime ||
                  row.latest_login_time ||
                  row.latestLoginTime
              )
            }}
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="160">
          <template #default="{ row }">
            {{ formatTime(row.latest_login_time || row.latestLoginTime) }}
          </template>
        </el-table-column>
        <el-table-column label="登录 IP" min-width="120">
          <template #default="{ row }">
            {{ row.latest_login_ip || row.latestLoginIp || "—" }}
          </template>
        </el-table-column>
        <el-table-column label="地区" min-width="120">
          <template #default="{ row }">
            {{
              row.location_city_label ||
              row.locationCityLabel ||
              row.user_regieon ||
              row.userRegieon ||
              "—"
            }}
          </template>
        </el-table-column>
        <el-table-column label="设备" min-width="120">
          <template #default="{ row }">
            {{ row.device_model || row.deviceModel || "—" }}
          </template>
        </el-table-column>
        <el-table-column label="余额" min-width="120">
          <template #default="{ row }">{{ walletText(row) }}</template>
        </el-table-column>
        <el-table-column label="好友" width="70">
          <template #default="{ row }">{{ row.friend_count ?? row.friendCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="群组" width="70">
          <template #default="{ row }">{{ row.group_count ?? row.groupCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.register_time || row.registerTime) }}</template>
        </el-table-column>
        <el-table-column label="注册 IP" min-width="120">
          <template #default="{ row }">{{ row.register_ip || row.registerIp || "—" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openDetail(uidOf(row))"
            >
              详情
            </el-button>
            <el-button
              v-if="canWrite()"
              link
              :type="isDisabled(row) ? 'success' : 'danger'"
              @click="toggleDisabled(row)"
            >
              {{ isDisabled(row) ? "解禁" : "禁用" }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.page_size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="load"
          @size-change="
            query.page = 1;
            load();
          "
        />
      </div>
    </div>

    <el-dialog v-model="createVisible" title="创建用户" width="440px">
      <el-form label-width="80px">
        <el-form-item label="昵称"><el-input v-model="createForm.nickname" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="createForm.sex" clearable placeholder="可选" style="width: 100%">
            <el-option label="男" value="1" />
            <el-option label="女" value="2" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="genVisible" title="批量生成任务" width="440px">
      <el-form label-width="80px">
        <el-form-item label="统一密码">
          <el-input v-model="genForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="genForm.count" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="genForm.sex" clearable placeholder="可选" style="width: 100%">
            <el-option label="男" value="1" />
            <el-option label="女" value="2" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitGen">提交任务</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="detailOpen"
      :title="detailUid ? `用户详情 · ${detailUid}` : '用户详情'"
      direction="rtl"
      size="640px"
      destroy-on-close
    >
      <UserDetailView
        v-if="detailUid"
        :user-uid="detailUid"
        embedded
        @updated="load"
      />
    </el-drawer>
  </div>
</template>
