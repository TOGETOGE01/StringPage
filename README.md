# 弦页 · StringPage

面向 Android 手机和平板的本地 Guitar Pro 读谱、跟练和编辑应用。支持谱面合成播放、GP8 内嵌录音和 PDF 六线谱转换。

An Android Guitar Pro score reader, practice player, and touch editor for phones and tablets.

当前源码版本：**1.36.0** · 最低系统：**Android 8.0 / API 26** · 项目代码：[MIT](LICENSE)

1.36.0 更新了手机和平板阅读界面，加入谱面 A/B 选段，为所有播放音源提供可调节拍器。[查看更新说明](docs/RELEASE_1.36.0.md)

当前优先改进读谱、声音与谱面同步、手机和平板体验。1.17.0 修复原曲暂停回退、音源切换和界面重建后练习位置丢失，以及手机横屏谱面被挤没的问题；详细范围见 [1.17.0 说明](docs/PRACTICE_1.17.0.md)。

1.14.1 修复部分原生 GP 文件的 ZIP 长度描述顺序兼容问题，可直接读取原文件的 RSE 设置，无需手动重新封装。读取时仍校验内容 CRC、实际大小和资源上限，不改写原文件。

## 下载与安装

**[直接下载 StringPage 1.36.0 APK](https://github.com/TOGETOGE01/StringPage/releases/download/v1.36.0/StringPage-1.36.0.apk)** · [版本说明与附件](https://github.com/TOGETOGE01/StringPage/releases/tag/v1.36.0) · [所有版本](https://github.com/TOGETOGE01/StringPage/releases)

下载 `.apk` 文件到安卓设备安装，最低 Android 8.0。该安装包包含 ARM64、ARMv7、x86 和 x86_64，沿用项目发布签名。Release 页面中的 `Source code` 是源码压缩包，直接安装请选 APK 附件。

SHA-256 为 `39a0290e61da660417c9c38e7e7bb1ff9b64a46141ab7ee59502d8868af8e8ba`。可下载 [SHA-256 校验文件](https://github.com/TOGETOGE01/StringPage/releases/download/v1.36.0/SHA256-1.36.0.txt)。

**[下载 Strat-Guitar 电吉他音色库（.gp7bank，52.8 MiB）](https://github.com/TOGETOGE01/StringPage/releases/download/v1.35.0/Strat-Guitar.gp7bank)** · [音色库获取与导入指南](docs/USER_GUIDE.md#音色库管理与单音试听)

音色库版权归 Arobas Music 所有。下载后在弦页选择「更多 → 原厂音色库 · 采样预览 → 导入音色库」。默认的 GeneralUser GS 合成音源随 APK 安装。

## 功能

- **本地读谱**：读取 `.gp3`、`.gp4`、`.gp5`、`.gpx`、`.gp`，提供五线谱、六线谱、组合显示、声部切换、缩放和全屏。
- **界面主题**：从「更多 → 界面主题」切换暖陶、松林、海蓝和夜墨，选择会保存到本机。
- **歌词**：歌词在原声部的谱线下方与对应节拍对齐。同时显示五线谱和六线谱时，歌词排在六线谱下方。
- **触屏编辑**：点工具栏「编辑」后，可直接点音符或品位数字，修改音高、品位和技法。支持新建乐谱、编辑轨道与小节、撤销和恢复草稿。
- **录音与导出**：导入本地录音，使用波形校准同步点。可导出 GP 兼容副本、MIDI、WAV 合成混音和打印版 PDF。
- **曲库管理**：批量导入、损坏文件反馈、最近打开的折叠与搜索、单项及批量移除记录。移除记录不会删除原始谱文件。
- **内嵌录音**：播放 GP8 文件内已有的 Audio Track，支持暂停、跳转、保持音高的变速、小节循环与同步跟谱。
- **按谱合成**：内置 GeneralUser GS SoftSynth 1.44 音色。吉他声部可选跟随原谱、清音、过载和失真，选择随本地乐谱保存。支持声部音量、静音、独奏、节拍器与预备拍。
- **吉他效果器**：从「更多 → 吉他效果器」选择 34 套内置预设，也可搭配两块可交换顺序的踏板、音箱、箱体、调制、延迟、混响和均衡。首次使用时准备对应声部的干声，此后可在播放中试听。「应用」按谱保存设置，「取消」恢复打开时的设置。
- **跟练**：点谱设置 A/B 循环、10%–100% 自定义播放速度、逐轮加速练习，以及手机、横竖屏和平板分屏布局。所有播放音源支持跟谱或自定义 BPM，可选节奏细分。
- **PDF 转 GP**：从“更多 → PDF 转 GP”选择电子吉他谱或清晰的单声部六线谱扫描件。可转换整份 PDF 或指定连续小节，选择声部，校对调弦、时值、指法和技法，试听并保存 `.gp`。电子谱支持传统索引、Flate 压缩索引和对象流、增量保存、原方向缩放及矩形裁切后的嵌套绘图。支持部分五线谱与 TAB 并排的布局、换拍、揉弦、滑音、击弦、勾弦和带指法的装饰音。可识别自然、人工、拨片、点弦、半泛音与反馈泛音，以及标出的触弦位置。推弦支持预推、继续推高、保持、完全或部分释放及延音衔接，时机按谱面估算。可识别带节奏六线谱上的快速拨弦，以及 Guitar Pro 电子谱上可确认的颤音杆降音、抬音、保持和回弹曲线。跨拍颤音杆可能需要对照原谱校对。扫描件支持部分基础指法与节奏，拍号、速度、调弦及复杂技法请对照原谱。
- **阅读状态**：音源切换保留位置、循环、速度和音量；系统重建界面后恢复声部、谱表、缩放及练习位置，保持暂停。已准备的采样音频需重新准备。
- **采样播放**：导入用户自行提供的 `.gp7bank`，为乐器声部或鼓组击打音分配采样库。准备完成后支持实时音箱、保持音高的变速与小节循环。

详细用法见[用户指南](docs/USER_GUIDE.md)，当前范围见 [PRODUCT_SCOPE.md](PRODUCT_SCOPE.md)。

## RSE 实现状态

**完整 Guitar Pro RSE 尚未还原，不能保证声音与桌面版一致。**

完整 RSE 研究已退出当前练谱目标；已有实现保留，本阶段优先处理影响读谱、常用技法、同步和设备稳定性的问题。

目前已接入音色库读取、采样树查找、逐音演奏信息、重音与力度补偿、采样起点、起音/释放包络、重采样、击勾弦与点弦，以及弦乐滑音的音段生成和播放同步。1.14.0 增加滑入、滑出、移位滑音、连滑及部分断奏组合处理。1.15.0 接入谱内自定义滑入时长、距离、起点比例，以及滑出距离和起点比例；在原厂采样按谱播放模式下生效。

1.16.0 将可读取原始 GPIF 逐音信息的弯音接入原厂采样播放，包括预弯、保持、继续弯音、回落、延音链修正及与断奏的处理顺序。曲线在每个采样声音上独立作用，保留原始谱面和内嵌录音。存在同音来源歧义的声部仍保留既有 MIDI 弯音路径。实现和验证边界见 [RSE 1.16.0 说明](docs/RSE_1.16.0.md)。

现有限制包括复杂技法组合、原生时序与动态幅度函数、拾音器混合、音箱/效果链和最终混音校准，逐项复刻这些内容不再作为当前完成条件。部分异常参数与非标准属性组合仍使用兼容策略。仓库不包含商业音色库、桌面 DLL 或 Guitar Pro 程序代码；采样模式需要自行提供有使用权的本地音色库。自定义滑音实现与对照范围见 [RSE 1.15.0 说明](docs/RSE_1.15.0.md)。



## 快速开始

1. 从上面的下载入口安装发布 APK，也可以按构建说明自行生成 Debug APK。
2. 点击「导入」，通过系统文件选择器选择一个或多个 GP 文件，也可以点「新建乐谱」。
3. 选择音源并播放。含可读取内嵌录音的文件可切换到“内嵌原曲”；没有录音的文件使用合成音源。
4. 点击谱面定位，设置速度及循环区间。采样播放需先导入并分配音色库，然后点击“准备播放”。

单个导入文件上限 128 MB。外部引用的录音不会自动下载；没有同步点的录音，其跟谱准确度取决于文件内容。完整录音无法按乐器拆分或独奏。

## 构建

需要以下开发环境：

| 项目 | 版本 |
| --- | --- |
| JDK | 17 或 21 |
| Android SDK | 36 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| Gradle | 9.2.1，已包含 Wrapper |
| Android Gradle Plugin | 9.0.1 |

用 Android Studio 打开仓库根目录，通过 SDK Manager 安装上述 SDK/NDK/CMake。命令行构建需配置 `ANDROID_HOME`，或在本机 `local.properties` 中设置 `sdk.dir`；该文件不提交。

```bash
git clone https://github.com/TOGETOGE01/StringPage.git
cd StringPage

# macOS / Linux
./gradlew assembleDebug lintDebug

# Windows PowerShell
.\gradlew.bat assembleDebug lintDebug
```

生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。原生模块包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，采用 16 KB ELF 段对齐。

### Release 签名

设置自己的签名环境变量后运行 `assembleRelease`：

- `STRINGPAGE_KEYSTORE`：密钥库路径。
- `STRINGPAGE_STORE_PASSWORD`：密钥库及密钥密码。
- `STRINGPAGE_KEY_ALIAS`：密钥别名。

不设置时生成未签名的 Release APK。仓库不提供签名密钥，也不要求把密码写入源码。

## 测试

连接 Android 设备或启动模拟器后运行：

```bash
./gradlew connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`。默认测试使用测试资源里的原创示例谱和数值语料，无需安装 Guitar Pro 或加载 Windows DLL。

部分集成测试依赖可选资源，未提供时会明确跳过：

- GP8/OGG 上游兼容夹具：在 PowerShell 执行 `./tools/fetch-test-fixture.ps1` 单独下载，仅供本机验证，不提交仓库。
- 真实采样播放：Debug 应用私有目录 `files/rse-reference.gp7bank`，已验证参考库为 12-Steel。
- 96 kHz 采样率测试：`files/rse-source-rate-reference.gp7bank`，已验证参考库为 BongoLow-Percu。

1.14.0 发布前曾在 Android 15 x86_64 模拟器上验证：完整回归 **137 项通过、0 失败、0 跳过**，签名 Release 专项 **62 项通过**；Lint 为 0 错误、51 项警告。该结果使用了上述本机可选资源，不代表没有资源的全新环境也会执行全部测试，更不代表完整 RSE 等价或真机认证。

1.16.0 本地验收：签名 Release 完整回归 **154 项通过、0 失败、0 跳过**，播放专项 **9 项通过**；Lint 为 0 错误、49 项警告。覆盖原生弯音曲线、私有参考库实际采样音频及真实 MediaPlayer 的暂停变速、跟谱时钟和循环；详细证据及未完成范围见 [RSE 1.16.0 说明](docs/RSE_1.16.0.md)。验证设备仍为 Android 15 x86_64 模拟器。

1.17.0 本地验收：签名 Release **161 项回归通过、0 失败、0 跳过**，另完成 **7 项阅读/恢复专项**和 **7 类窗口及字体组合**检查，并人工核对截图。验证范围和保存状态的限制见 [1.17.0 说明](docs/PRACTICE_1.17.0.md)。真机暂未连接，模拟器结果不代表小米 Pad 7 Ultra / ARM64 验收完成。

## 源码结构

```text
app/src/main/java/com/stringpage/app/
  MainActivity.kt         读谱界面与播放协调
  EmbeddedAudio.kt        内嵌录音与外部媒体时钟
  GpBatchImporter.kt      批量导入
  RecentLibrary.kt        最近打开记录
  rse/                    采样库读取、选样、演奏事件及渲染
app/src/main/cpp/         原生采样率转换模块及 r8brain
app/src/main/assets/      通用音色与许可证
app/src/androidTest/      设备集成测试、示例谱和数值回归语料
docs/USER_GUIDE.md        详细使用与限制说明
```

## 参与开发

欢迎提交问题或 Pull Request。请附上设备、Android版本、复现步骤及所用音源；测试谱应为可公开的最小示例。具体说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证与致谢

项目新增代码采用 [MIT License](LICENSE)。第三方代码、字体和音色保留其各自许可证，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。主要依赖包括 alphaTab、AndroidX、GeneralUser GS、r8brain、Bravura 与 AlphaSkia；MIT 不替代这些资源的原有授权。

StringPage 是独立项目，与 Arobas Music / Guitar Pro 无隶属或官方合作关系。Guitar Pro 和 RSE 名称仅用于说明文件兼容性与实现目标。

