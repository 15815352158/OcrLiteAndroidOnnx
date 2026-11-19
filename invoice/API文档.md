# 发票防重检测服务 API 文档

## 基础说明

- 请求地址前缀：`https://${IP}:${PORT}`
  - `IP` 默认 `10.129.0.239`
  - `PORT` 默认 `12888`（可在根目录 `.env` 中修改 `HTTPS_PORT`）
- 传输方式：仅 HTTPS（自签名证书场景 curl 需加 `-k`）
- 时间格式：所有时间均为 UTC+8，字符串格式 `YYYY-MM-DD HH:MM:SS`

### 鉴权方式

| 方式 | 请求头 | 适用接口 |
|---|---|---|
| JWT | `Authorization: Bearer ${TOKEN}` | 除登录外的全部接口（登录后获得 TOKEN） |
| API Token | `X-API-Token: ${API_TOKEN}` | 仅"添加发票"接口，供外部系统调用 |

### 通用错误格式

```json
{ "detail": "错误描述" }
```

| HTTP 状态码 | 含义 |
|---|---|
| 400 | 参数校验失败 |
| 401 | 未登录 / JWT 无效或过期 / API Token 无效或过期 |
| 403 | 非管理员，或删除发票时密码二次校验失败 |
| 404 | 资源不存在 |
| 409 | 发票号码重复（响应体格式特殊，见添加发票接口） |

### curl 环境变量约定

以下示例均假定已设置：

```bash
export IP=10.129.0.239
export PORT=12888
export TOKEN=<登录返回的 JWT>
export API_TOKEN=<用户生成的 API Token>
```

---

## 1. 登录

`POST /api/auth/login`

无需鉴权。

**入参（JSON）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 用户名，不能为空 |
| password | string | 是 | 密码，不能为空 |

**响应（200）**

| 字段 | 类型 | 说明 |
|---|---|---|
| token | string | JWT，有效期 12 小时 |
| username | string | 用户名 |
| isAdmin | bool | 是否管理员（用户名为 admin 即管理员） |

失败返回 401。

```bash
curl -ksS -X POST "https://${IP}:${PORT}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<密码>"}'
```

---

## 2. 当前用户信息

`GET /api/auth/me`

**响应（200）**：`{ "username": string, "isAdmin": bool }`

```bash
curl -ksS "https://${IP}:${PORT}/api/auth/me" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 3. 发票分页查询

`GET /api/invoices`

**入参（Query）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page | int | 否 | 页码，从 1 开始，默认 1 |
| pageSize | int | 否 | 每页条数，默认 10；仅允许 10/20/50/100，其它值按 10 处理 |

**响应（200）**

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 总条数 |
| page / pageSize | int | 实际生效的分页参数 |
| items | array | 当页数据，按创建时间倒序 |
| items[].id | int | 发票记录 Id |
| items[].invoiceNumber | string | 发票号码 |
| items[].createdAt | string | 创建时间（UTC+8） |

```bash
curl -ksS "https://${IP}:${PORT}/api/invoices?page=1&pageSize=20" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 4. 添加发票（防重检测）

`POST /api/invoices`

鉴权：JWT 或 API Token 二选一。

**入参（JSON）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| invoiceNumber | string | 是 | 发票号码，不为空且 ≤20 字符 |

**响应（200，新发票录入成功）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int | 新记录 Id |
| invoiceNumber | string | 发票号码 |
| createdAt | string | 创建时间（UTC+8） |

**响应（409，发票号码重复，数据库无变化）**

| 字段 | 类型 | 说明 |
|---|---|---|
| code | string | 固定 `DUPLICATE` |
| message | string | 含冲突记录的 Id 与创建时间 |
| conflict.id | int | 已存在记录的 Id |
| conflict.createdAt | string | 已存在记录的创建时间 |

```json
{
  "code": "DUPLICATE",
  "message": "发票号码已存在，与记录 Id=123（2026-11-01 10:20:30）重复",
  "conflict": { "id": 123, "createdAt": "2026-11-01 10:20:30" }
}
```

前端 JWT 方式：

```bash
curl -ksS -X POST "https://${IP}:${PORT}/api/invoices" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"invoiceNumber":"044123456789"}'
```

外部系统 API Token 方式：

```bash
curl -ksS -X POST "https://${IP}:${PORT}/api/invoices" \
  -H "X-API-Token: ${API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"invoiceNumber":"044123456789"}'
```

---

## 5. 删除发票（仅 admin）

`DELETE /api/invoices`

仅管理员可调用，且请求体必须携带 `password`（admin 当前登录密码）做二次校验，校验失败返回 403，不执行任何删除。

**入参（JSON）**：`password` 必填，删除条件 `ids` / `start`+`end` / `before` 三选一

| 字段 | 类型 | 说明 |
|---|---|---|
| password | string | admin 当前密码 |
| ids | int[] | 方式一：按发票 Id 列表删除，非空整数数组 |
| start / end | string | 方式二：按创建时间段删除（必须成对，`YYYY-MM-DD HH:MM:SS`，start ≤ end） |
| before | string | 方式三：删除创建时间早于该时刻的数据 |

**响应（200）**：`{ "deleted": int }` 实际删除条数

```bash
# 按 Id 删除
curl -ksS -X DELETE "https://${IP}:${PORT}/api/invoices" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"password":"<admin密码>","ids":[4,5,6]}'

# 按时间段删除
curl -ksS -X DELETE "https://${IP}:${PORT}/api/invoices" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"password":"<admin密码>","start":"2026-01-01 00:00:00","end":"2026-01-31 23:59:59"}'

# 删除指定时间之前
curl -ksS -X DELETE "https://${IP}:${PORT}/api/invoices" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"password":"<admin密码>","before":"2026-01-01 00:00:00"}'
```

---

## 6. 用户列表（仅 admin）

`GET /api/users`

**响应（200）**：数组

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int | 用户 Id |
| username | string | 用户名 |
| createdAt | string | 创建时间（UTC+8） |

```bash
curl -ksS "https://${IP}:${PORT}/api/users" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 7. 添加用户（仅 admin）

`POST /api/users`

**入参（JSON）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | 不能为空、不能与已有用户重复 |
| password | string | 是 | 长度 6–10 位 |

**响应（200）**：`{ "id": int }`；校验失败返回 400

```bash
curl -ksS -X POST "https://${IP}:${PORT}/api/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"<6-10位密码>"}'
```

---

## 8. 修改密码

`PUT /api/users/password`

**入参（JSON）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| newPassword | string | 是 | 新密码，长度 6–10 位 |
| oldPassword | string | 条件 | 修改自己的密码时必填（含 admin） |
| username | string | 条件 | admin 重置他人密码时指定目标用户名，此时不需要 oldPassword；不带则修改自己的密码 |

**响应（200）**：`{ "ok": true }`；原密码错误 / 长度非法 / 普通用户改他人密码返回 400

```bash
# 修改自己的密码
curl -ksS -X PUT "https://${IP}:${PORT}/api/users/password" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"<旧密码>","newPassword":"<新密码>"}'

# admin 重置他人密码
curl -ksS -X PUT "https://${IP}:${PORT}/api/users/password" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","newPassword":"<新密码>"}'
```

---

## 9. 删除用户（仅 admin）

`DELETE /api/users/{id}`

**入参（Path）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int | 用户 Id |

**响应（200）**：`{ "ok": true }`；该用户的 API Token 一并删除。admin 行不可删除（400）；用户不存在返回 404

```bash
curl -ksS -X DELETE "https://${IP}:${PORT}/api/users/3" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 10. 生成 API Token

`POST /api/tokens`

**入参（JSON）**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| validDays | int / null | 否 | 有效天数（正整数）；`null` 或不传表示永久 |

**响应（200）**

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int | Token 记录 Id |
| token | string | Token 明文（64 位十六进制） |
| expiresAt | string / null | 过期时间（UTC+8），永久为 null |

```bash
# 有效期 30 天
curl -ksS -X POST "https://${IP}:${PORT}/api/tokens" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"validDays":30}'

# 永久有效
curl -ksS -X POST "https://${IP}:${PORT}/api/tokens" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"validDays":null}'
```

---

## 11. Token 列表

`GET /api/tokens`

返回当前用户自己的 Token 列表。

**响应（200）**：数组，元素字段 `id` / `token` / `expiresAt`（永久为 null）/ `createdAt`

```bash
curl -ksS "https://${IP}:${PORT}/api/tokens" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 12. 删除 Token

`DELETE /api/tokens/{id}`

仅能删除自己的 Token。

**响应（200）**：`{ "ok": true }`；不存在或非本人返回 404

```bash
curl -ksS -X DELETE "https://${IP}:${PORT}/api/tokens/2" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 外部系统调用示例（完整流程）

外部系统只需一个 API Token 即可完成发票防重录入：

```bash
export IP=10.129.0.239
export PORT=12888
export API_TOKEN=<管理员或用户分配的 Token>

# 录入发票号码；HTTP 200=新发票，HTTP 409=重复（message 中含冲突记录 Id 与时间）
curl -ksS -X POST "https://${IP}:${PORT}/api/invoices" \
  -H "X-API-Token: ${API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"invoiceNumber":"044123456789"}'
```
