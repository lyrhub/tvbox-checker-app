# TVBox Checker

Android App，用于检测 TVBox 源中站点的可用性，搜索资源并测试播放链接速度。

## 功能

### 📡 源检测 (Tab 1)
1. **源加载** - 输入 TVBox 源 URL，自动解析 JSON 配置
2. **连通性测试** - 并发测试所有站点 API 是否可连通，显示延迟和状态
3. **筛选与勾选** - 按有效/无效/全部筛选，手动勾选需要保留的站点
4. **导出新源** - 将选中的站点生成新的 TVBox 源 JSON
   - 保存到本地存储
   - 上传到 GitHub Pages
   - 上传到 Cloudflare Pages

### 🎬 搜索测试 (Tab 2)
1. **加载源** - 输入 TVBox 源 URL，自动识别可搜索的站点 (type 0/1/3)
2. **聚合搜索** - 输入关键词，同时在所有可搜索站点中搜索影视资源
3. **获取详情** - 点击搜索结果，获取播放源和集数列表
4. **播放链接测速** - 对各播放源的链接进行可用性和速度测试
   - 支持 m3u8 内容验证
   - 显示响应延迟
   - 标记不可用/超时链接

## 支持的站点类型

| Type | 说明 | 搜索支持 |
|------|------|---------|
| 0 | CMS JSON (苹果CMS v10) | ✅ 完整支持 |
| 1 | CMS XML/JSON (海洋CMS等) | ✅ 完整支持 |
| 3 | Spider (JAR/DEX 爬虫) | ⚠️ 仅支持有 ext URL 的站点 |
| 4 | XPath/JSONPath 规则 | ❌ 暂不支持 |

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- OkHttp 4.12 (网络请求)
- Kotlin Coroutines (并发测试)
- Kotlinx Serialization (JSON 解析)
- Android ViewModel + StateFlow

## 构建

### 本地构建

```bash
./gradlew assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 自动构建

Push 到 `main` 分支后会自动触发构建：
- Debug APK 在 Actions → Artifacts 中下载
- 每次 push 自动创建 Release 并附带 APK

## 使用方法

### 源检测
1. 打开 App，输入 TVBox 源的 URL 地址
2. 点击「加载源」解析配置
3. 点击「开始测试」对所有站点进行连通性检测
4. 测试完成后，默认选中所有有效站点
5. 可手动调整选择，或使用筛选按钮快速操作
6. 点击「保存本地」或「上传云端」导出新源

### 资源搜索
1. 切换到「搜索测试」标签页
2. 输入同一个源地址并加载
3. 输入影视名称进行聚合搜索
4. 点击搜索结果查看播放源详情
5. 点击「测速」按钮测试播放链接的可用性和速度

## 云端上传配置

### GitHub Pages
- 需要 Personal Access Token（需 `repo` 权限）
- 仓库需要已开启 GitHub Pages（设置 gh-pages 分支）

### Cloudflare Pages
- 需要 Cloudflare API Token
- 需要 Account ID 和已创建的 Pages 项目名
