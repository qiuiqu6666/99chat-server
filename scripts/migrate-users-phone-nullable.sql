-- 后台创建用户可不绑定手机（IM 号 + 密码登录）
ALTER TABLE users MODIFY phone varchar(20) NULL;
