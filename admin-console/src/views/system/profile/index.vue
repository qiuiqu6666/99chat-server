<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { changePasswordApi, getProfile, putProfile } from '@/service/api/profile';
import { unwrap } from '@/service/http';
import { errMessage } from '@/utils/format';
import { DEFAULT_AVATAR, avatarSrc } from '@/utils/adminTable';

defineOptions({ name: 'system_profile' });
const profile = reactive<Record<string, unknown>>({});
const form = reactive({ nickname: '', display_name: '', email: '', phone: '', avatar: '' });
const pwd = reactive({ old_password: '', new_password: '' });
const saving = ref(false);
const rows = computed(() => [
  { label: '账号', value: String(profile.username ?? '—') },
  { label: '角色', value: String(profile.role ?? '—') },
  { label: '权限', value: Array.isArray(profile.permissions) ? profile.permissions.join('、') : '—' },
  { label: '最近登录', value: String(profile.last_login_time ?? '—') },
  { label: '登录 IP', value: String(profile.last_login_ip ?? '—') },
  { label: '创建时间', value: String(profile.created_at ?? '—') },
  { label: '状态', value: String(profile.status ?? '—') }
]);

onMounted(async () => {
  Object.assign(profile, unwrap(await getProfile()));
  const user = (profile.user && typeof profile.user === 'object' ? profile.user : profile) as Record<string, unknown>;
  Object.assign(profile, user);
  form.nickname = String(user.nickname ?? '');
  form.display_name = String(user.display_name ?? user.displayName ?? '');
  form.email = String(user.email ?? '');
  form.phone = String(user.phone ?? '');
  form.avatar = String(user.avatar ?? '');
});
async function save() {
  saving.value = true;
  try {
    await putProfile({
      nickname: form.nickname,
      display_name: form.display_name,
      email: form.email,
      phone: form.phone,
      avatar: form.avatar
    });
    window.$message?.success('已保存');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}
async function changePwd() {
  saving.value = true;
  try {
    await changePasswordApi(pwd.old_password, pwd.new_password);
    window.$message?.success('密码已修改');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="个人设置" />
    <NCard title="资料" class="mb-12px">
      <div class="mb-12px flex items-center gap-12px">
        <NAvatar round :size="48" :src="avatarSrc(form.avatar)" :fallback-src="DEFAULT_AVATAR" />
        <NInput v-model:value="form.avatar" placeholder="头像地址" />
      </div>
      <NDataTable class="mb-12px" bordered striped :single-line="false" size="small" :columns="[{ title: '项目', key: 'label', width: 140 }, { title: '内容', key: 'value' }]" :data="rows" />
      <NForm label-placement="left" label-width="80">
        <NFormItem label="昵称"><NInput v-model:value="form.nickname" /></NFormItem>
        <NFormItem label="显示名"><NInput v-model:value="form.display_name" /></NFormItem>
        <NFormItem label="邮箱"><NInput v-model:value="form.email" /></NFormItem>
        <NFormItem label="手机号"><NInput v-model:value="form.phone" /></NFormItem>
      </NForm>
      <NButton type="primary" :loading="saving" @click="save">保存</NButton>
    </NCard>
    <NCard title="修改密码">
      <NForm label-placement="left" label-width="80">
        <NFormItem label="原密码"><NInput v-model:value="pwd.old_password" type="password" /></NFormItem>
        <NFormItem label="新密码"><NInput v-model:value="pwd.new_password" type="password" /></NFormItem>
      </NForm>
      <NButton type="warning" :loading="saving" @click="changePwd">修改密码</NButton>
    </NCard>
  </div>
</template>
