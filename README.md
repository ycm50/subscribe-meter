# 额度看板

安卓 App:一个界面看全 **OpenCode Zen / Go**、**Command Code GOAT**、**OpenDesign Cloud**、
**new-api / one-api / sub2api** 的订阅额度与余额。

## 安装

从 [Releases](../../releases) 下载最新的 `subscribe-meter-<版本号>.apk`,装到手机上即可
(Android 8.0 及以上)。

## 添加额度源

打开 App,点右下角 **+**,选类型:

| 类型 | 需要的凭据 | 说明 |
|---|---|---|
| OpenCode Zen / Go | API Key(Go)或控制台 Cookie(Zen) | Go 看 5 小时 / 本周 / 本月三档;Zen 看预付费余额 |
| Command Code GOAT | API Key | 5 小时 / 本周 / 月度池三档 |
| OpenDesign Cloud | 浏览器授权登录 | 点「登录 OpenDesign」,在浏览器确认验证码即可,不用填 Key |
| new-api / one-api | 站点地址 + 访问令牌 | 自动识别 one-api / new-api / sub2api |
| 自定义 JSON | 接口地址 + JSON 路径 | 任何返回 JSON 的额度接口 |

同一种类型可以加多个,比如两个不同站点的 new-api。

## 看额度

每个额度源一张卡,按档位列出 **已用 / 总额 + 进度条 + 距重置时间**:

- 超过 70% 变黄,超过 90% 变红
- 顶部显示上次刷新时间;右上角 ↻ 手动刷新,⚙ 进设置
- 打开 App 会先显示上次的读数,再自动刷新

## 填总额(可选)

有些接口不给金额(只给百分比),或者单位不是美元(比如 credits)。这时可以在额度源的设置里
填 **各窗口总额(美元)**:

- **留空 = 直接用接口返回的额度**,这是推荐做法,绝大多数情况不用管
- 三档额度源(OpenCode / Command Code / OpenDesign)**填任意一档就行**,其余档位会按接口给出的数值比例自动推导
  - 例:OpenDesign 的 7 天额度是 150000 credits、折合 $30,就只在「7 天」填 30,5 小时会自动算成 $10
- new-api / 自定义这类本来就有金额的,留空即可

输入框下面会显示接口实际返回的额度,方便判断要不要填。

## 设置

右上角 ⚙:

- **自动刷新间隔**:默认 15 分钟
- **额度吃紧提醒**:最紧张的档位超过阈值(默认 85%)时发通知
- **HTTP 代理**:需要走代理才能访问接口时填,如 `127.0.0.1:8080`
- **配置导入导出**:复制配置 JSON / 粘贴导入。**不含密钥**,换设备后需要重新填

## 安全

密钥、令牌、Cookie 都存在手机系统的加密存储里(Android Keystore,AES-256-GCM),
不会写进配置文件,导出配置时也不会带出去,App 里只显示掩码。

## 常见问题

**显示「登录态失效」**
OpenDesign 的登录态会随重新登录而轮换,在额度源设置里重新点一次「登录 OpenDesign」即可。

**显示「接口结构可能已变化」**
接口返回里读不到有效的额度数值。这种情况不会显示成 0%,而是明确报错。

**读数是 0%**
0% 是真实读数(确实一点没用);读不到数据时会显示报错文案,两者不会混淆。

## 构建(给开发者)

需要 JDK 17 和 Android SDK(compileSdk 36):

```sh
gradle :app:assembleDebug
```

APK 在 `app/build/outputs/apk/debug/`。

也可以到 GitHub 的 **Actions → Build APK → Run workflow** 手动填版本号构建,
会生成同名 tag 与 release(同一版本号重复构建会覆盖旧的 tag 和 release)。

## License

MIT
