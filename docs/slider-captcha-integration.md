# 滑块验证前端对接指南

## 1. 概述

本项目滑块验证采用**前后端分离**模式：

- **后端**：只负责生成背景图、随机确定缺口位置、存储/校验数据
- **前端**：负责渲染背景图、根据后端返回的坐标渲染真缺口/假缺口/拼图块、监听拖动事件

后端使用 Redis 存储滑块验证数据，验证成功后立即删除该 `token`，防止重放攻击。验证数据默认过期时间为 5 分钟。

**图片尺寸：320 × 170 像素**

> ⚠️ 安全提示：后端不再在图片上预绘缺口或拼图块，所有视觉元素由前端根据后端返回的数据自行渲染。这样可以防止通过分析图片直接获取答案。

## 2. 接口说明

### 2.1 初始化滑块验证（前后端分离模式）

后端返回 JSON 格式数据，包含背景图 Base64、token、真缺口位置、假缺口列表，前端根据这些数据自行渲染验证码界面。

请求：
```
GET /auth/slider/init
```

响应：
- `Content-Type: application/json`
- HTTP Body: JSON 格式数据
- HTTP Headers:
  - `X-Captcha-Token`: uuid 字符串，一次性验证标识
  - `X-Captcha-Width`: `320`
  - `X-Captcha-Height`: `170`

响应示例：
```json
{
  "token": "ff30e76c-5dae-4836-97f0-852d2fec7086",
  "targetX": 154,
  "targetY": 45,
  "gapX": 154,
  "gapY": 45,
  "gapWidth": 50,
  "gapHeight": 50,
  "fakeGaps": [
    {"x": 85, "y": 60, "width": 50, "height": 50},
    {"x": 200, "y": 90, "width": 50, "height": 50}
  ],
  "background": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA..."
}
```

字段说明：

| 字段 | 类型 | 说明 |
|------|------|------|
| `token` | string | 一次性验证标识 |
| `targetX` | int | 真缺口在背景图中的 X 坐标（像素），范围 50-220 |
| `targetY` | int | 真缺口在背景图中的 Y 坐标（像素），范围 30-120 |
| `gapX` | int | 真缺口形状的左上角 X 坐标（与 targetX 相同） |
| `gapY` | int | 真缺口形状的左上角 Y 坐标（与 targetY 相同） |
| `gapWidth` | int | 缺口宽度（默认 50 像素） |
| `gapHeight` | int | 缺口高度（默认 50 像素） |
| `fakeGaps` | array | 假缺口列表，用于干扰机器识别 |
| `fakeGaps[].x` | int | 假缺口 X 坐标 |
| `fakeGaps[].y` | int | 假缺口 Y 坐标 |
| `fakeGaps[].width` | int | 假缺口宽度 |
| `fakeGaps[].height` | int | 假缺口高度 |
| `background` | string | 背景图 Base64 编码（可直接用于 `<img src="...">`） |

> 注意：`targetX` 和 `targetY` 是**真缺口**的位置，是用户需要将拼图块滑动到的目标位置。`fakeGaps` 是干扰项，没有对应的拼图块。

### 2.2 验证滑块位置

请求：
```
POST /auth/slider/verify
Content-Type: application/json
```

请求体示例：
```json
{
  "token": "ff30e76c-5dae-4836-97f0-852d2fec7086",
  "x": 154,
  "y": 45
}
```

响应示例：
```json
{
  "success": true,
  "message": "验证成功"
}
```

失败示例：
```json
{
  "success": false,
  "message": "滑块验证失败"
}
```

字段说明：

| 字段 | 类型 | 必选 | 说明 |
|------|------|------|------|
| `token` | string | 是 | 初始化接口返回的唯一标识 |
| `x` | int | 是 | 用户滑块 X 位置（像素） |
| `y` | int | 否 | 用户滑块 Y 位置（像素） |
| `success` | boolean | - | 是否验证通过 |
| `message` | string | - | 验证结果提示 |

## 3. 前端对接流程

### 3.1 获取验证码数据

```js
async function initSliderCaptcha() {
  const resp = await fetch('/auth/slider/init');
  if (!resp.ok) {
    throw new Error('滑块验证初始化失败');
  }

  // 从 Header 获取 token
  const token = resp.headers.get('X-Captcha-Token');
  const width = resp.headers.get('X-Captcha-Width');
  const height = resp.headers.get('X-Captcha-Height');

  // 解析 JSON 响应体
  const data = await resp.json();

  // 将 background Base64 转为图片 URL
  const bgImageUrl = 'data:image/png;base64,' + data.background;

  return {
    token,
    width,
    height,
    bgImageUrl,
    targetX: data.targetX,      // 真缺口位置（用于拼图块目标位置）
    targetY: data.targetY,
    gapX: data.gapX,            // 缺口左上角 X
    gapY: data.gapY,            // 缺口左上角 Y
    gapWidth: data.gapWidth,    // 缺口宽度
    gapHeight: data.gapHeight,  // 缺口高度
    fakeGaps: data.fakeGaps     // 假缺口数组
  };
}
```

### 3.2 前端渲染逻辑

前端需要根据后端返回的数据，自行完成以下渲染：

#### 3.2.1 渲染背景图

```js
// 直接使用 Base64 图片
document.getElementById('captcha-bg').src = bgImageUrl;
```

#### 3.2.2 渲染真缺口（凹槽）

使用 Canvas 2D API，根据 `gapX`, `gapY`, `gapWidth`, `gapHeight` 绘制缺口：

```js
function drawGap(ctx, x, y, width, height) {
  ctx.save();

  // 1. 绘制阴影（增加深度感）
  ctx.fillStyle = 'rgba(0, 0, 0, 0.35)';
  ctx.translate(x + 2, y + 2);
  drawPuzzleShape(ctx, width, height);
  ctx.fill();
  ctx.translate(-(x + 2), -(y + 2));

  // 2. 绘制半透明黑色遮罩（约 45%）
  ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
  drawPuzzleShape(ctx, x, y, width, height);
  ctx.fill();

  // 3. 绘制 1.5px 浅灰白描边
  ctx.strokeStyle = 'rgba(200, 200, 200, 0.6)';
  ctx.lineWidth = 1.5;
  drawPuzzleShape(ctx, x, y, width, height);
  ctx.stroke();

  ctx.restore();
}
```

#### 3.2.3 渲染假缺口（干扰项）

遍历 `fakeGaps` 数组，在对应位置绘制假缺口：

```js
function drawFakeGaps(ctx, fakeGaps) {
  fakeGaps.forEach(gap => {
    ctx.save();
    ctx.fillStyle = 'rgba(100, 100, 100, 0.6)';
    ctx.fillRect(gap.x, gap.y, gap.width, gap.height);
    ctx.strokeStyle = 'rgba(80, 80, 80, 0.5)';
    ctx.lineWidth = 1.5;
    ctx.strokeRect(gap.x, gap.y, gap.width, gap.height);
    ctx.restore();
  });
}
```

#### 3.2.4 渲染拼图块

拼图块是从背景图对应位置抠出的真实内容，前端需要：

1. 从背景图 `targetX`, `targetY` 位置裁剪出拼图块区域
2. 应用拼图形状（带凹凸圆弧）
3. 添加阴影效果，使其浮在背景上

```js
function drawPuzzleBlock(ctx, bgImage, targetX, targetY, width, height) {
  // 创建拼图形状
  const shape = createPuzzleShape(0, 0, width, height);

  // 裁剪区域
  ctx.save();
  ctx.clip(shape);

  // 绘制背景图对应区域（拼图块真实内容）
  ctx.drawImage(
    bgImage,
    targetX, targetY, width, height,  // 源区域
    0, 0, width, height               // 目标区域
  );

  ctx.restore();

  // 添加阴影
  ctx.shadowColor = 'rgba(0, 0, 0, 0.5)';
  ctx.shadowBlur = 8;
  ctx.shadowOffsetX = 2;
  ctx.shadowOffsetY = 2;

  // 绘制拼图块边缘
  ctx.strokeStyle = 'rgba(0, 0, 0, 0.5)';
  ctx.lineWidth = 2;
  ctx.stroke(shape);
}
```

#### 3.2.5 拼图形状绘制函数

拼图形状包含凹凸圆弧，使用贝塞尔曲线绘制：

```js
function createPuzzleShape(x, y, w, h) {
  const path = new Path2D();
  const tabWidth = w / 4;
  const tabHeight = h / 3;

  // 基础矩形
  path.rect(x, y, w, h);

  // 添加左右凹凸（根据后端返回的形状参数）
  // 具体实现参考后端 createPuzzleShape 算法

  return path;
}
```

### 3.3 拖动交互与位置提交

前端监听拖动事件，计算用户拖动位置后提交验证：

```js
let startX = 0;

slider.addEventListener('mousedown', (e) => {
  startX = e.clientX;
});

document.addEventListener('mouseup', async (e) => {
  const deltaX = e.clientX - startX;
  const userX = 280 + deltaX; // 根据滑块初始位置计算
  const userY = 0; // 可根据实际布局调整

  // 提交验证
  const result = await verifySliderCaptcha(token, userX, userY);

  if (result.success) {
    alert('验证通过');
  } else {
    alert('验证失败，请重试');
    // 刷新验证码
  }
});
```

### 3.4 提交验证

```js
async function verifySliderCaptcha(token, x, y) {
  const resp = await fetch('/auth/slider/verify', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      token: token,
      x: x,
      y: y
    })
  });

  if (!resp.ok) {
    throw new Error('滑块验证请求失败');
  }

  return await resp.json();
}
```

## 4. 重要注意事项

- `token` 是一次性使用的：验证成功后该 `token` 会立即从 Redis 删除；重复提交同一个 `token` 会失败
- 后端允许误差范围为 `±5` 像素（X 轴和 Y 轴）
- 若验证失败或超时，应重新调用 `GET /auth/slider/init` 重新获取新的验证码
- 图片中包含 1 个真缺口和 2 个假缺口，假缺口无实际验证价值，仅用于干扰机器识别
- `targetX` 和 `targetY` 用于前端定位拼图块目标位置，实际验证结果由后端 `verify` 接口判断
- 该验证适合用于防刷、前端保护等场景，不建议作为唯一的安全认证机制

## 5. 图片生成算法说明（后端）

后端生成的图片只包含背景图，不在图上绘制缺口或拼图块：

1. **背景图**：从 4 张背景图中随机选择一张，缩放至 320×170
2. **真缺口、假缺口、拼图块**：**不再由后端绘制**，改为返回坐标数据给前端，由前端自行渲染

这种前后端分离模式的优势：
- 防止通过分析图片直接获取答案
- 前端可以使用更丰富的视觉效果（阴影、渐变、动画等）
- 后端逻辑更简单，只负责数据生成和校验
