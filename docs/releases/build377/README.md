# DSHA build377 修复报告

这份源码对应 Android 版本 `0.2.2-rc.5-20261125.1442-dsh0.2.2-rc.5`（版本码 377）。从[本地验收](%E6%9C%AC%E5%9C%B0%E9%AA%8C%E6%94%B6.md)可查看每项问题原先表现、修复位置、前后实测与失败重试。[成品系统实测](%E6%88%90%E5%93%81%E7%B3%BB%E7%BB%9F%E5%AE%9E%E6%B5%8B.md)记录签名包在模拟器里的结果，[遗留范围](%E9%81%97%E7%95%99%E8%8C%83%E5%9B%B4.md)列出证据仍未覆盖的场景。此次验收采用宿主、Android 模拟器、浏览器和 QEMU。

| 项目 | 值 |
| --- | --- |
| Android 签名时的本地提交 | `365719301ebf8c35f87edc24bdce4edb503c46d5` |
| 本分支源码提交 | [`b928479bee028d6a0a5eb1f3bfb7d48e8461190f`](https://github.com/SunsetRNE/DSHA-Sunset-mod/commit/b928479bee028d6a0a5eb1f3bfb7d48e8461190f) |
| 两者共同的 Git 源码树 | `d074ba67a971f2e16697483b5fb345e661e946e9`，7,880 个文件 |
| APK 的 SHA-256 | `f6b7f9ecf53e71a20bebf4ce23d3c8cf643311a7cee5435ad281d2dec70235f2`，255,591,272 字节，原件本地留存 |
| 签名证书指纹 | `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5` |
| 校验结果 | Windows 5,845 项、Linux 144 项，共 5,989 个不同测试方法通过。Release Lint 为 0 错误、649 警告和 30 提示。最终签名包通过 12 项成品检查。 |

### 源码和机器记录

构建前请看[构建说明](../../../BUILD.md)、[固定输入说明](../../fixed-build-inputs-local.md)和[十种语言中的简体中文改动说明](../build377-changes.zh.md)。五个只允许随完整应用分发的 proroot 原件由项目按作者固定版本恢复。[对应原生源码 ZIP](local-release/dsha-proot-talloc-source.zip)内含 PRoot、两个加载器与 talloc 的构建配方及固定源码输入，SHA-256 为 `bebb78e0ebdb74b494e3c160f0bee57f15eb34bf7bf1800e56fac03af5bbad27`，1,191,235 字节。

逐项核对可使用[本地交付回执](delivery-local.json)、[软件检测汇总](final4/software-summary.json)、[Windows 每个方法的运行结果](windows-r6/method-results.jsonl)、[Linux 测试汇总](linux-tools/linux-r6/summary.json)、[最终 APK 门禁](verification-final4/summary.json)和[正式、应急 QEMU 回执](qemu-04/receipt.json)。本地回执里的交付状态和时间均为签包当时的记录。

### 如何复核

在克隆的仓库里执行以下命令。第一条应输出上表的 Git 源码树，第二条应输出原生源码 ZIP 的摘要。第三条可离线逐项检查文档、验收附件、源码基线及报告清单。

```text
git rev-parse "b928479bee028d6a0a5eb1f3bfb7d48e8461190f^{tree}"
python -c "import hashlib,pathlib; print(hashlib.sha256(pathlib.Path('docs/releases/build377/local-release/dsha-proot-talloc-source.zip').read_bytes()).hexdigest())"
python docs/releases/build377/verify.py
```

各专项报告保留原来的环境、命令、参数和采样时点。历史命令中的 `F:/DSHA_RESTART` 是当时的工作区布局。其 `source/build377-local` 对应此仓库根目录，`artifacts/build377-local` 下公开的记录和工具位于本页目录。运行系统实验还需报告里列出的 SDK、工具链、模拟器及相应构建输入。文中 GitHub 相对链接可以直接打开；个人主目录和私人参考文章的位置已使用占位符。

[公开文件及原件摘要清单](publication.json)逐项登记 37 份原报告、运行记录、截图与源码 ZIP。报告中指向早期安装包、旧版私人设置账本、第三方文章和少数原始系统转储的链接可打开[本地留存目录](local-evidence.md)查看原因与校验值。

### 全部 37 份原报告

- [ARM64 普通应用首次部署复核](arm64-first-install/%E5%8E%9F%E7%94%9F%E9%A6%96%E6%AC%A1%E9%83%A8%E7%BD%B2%E5%A4%8D%E6%A0%B8.md) · `arm64-first-install/原生首次部署复核.md`
- [紧凑屏上的备份密码窗口](backup/compact-ui/%E9%AA%8C%E6%94%B6.md) · `backup/compact-ui/验收.md`
- [系统输入法打开时的备份按钮](backup/ime-device/%E9%AA%8C%E6%94%B6.md) · `backup/ime-device/验收.md`
- [系统键盘打开后的输入框和按钮](backup/landscape-device/%E9%AA%8C%E6%94%B6.md) · `backup/landscape-device/验收.md`
- [377 正式资源下的原生备份测试](backup/ui-377-worker/%E9%AA%8C%E6%94%B6.md) · `backup/ui-377-worker/验收.md`
- [build377 可移机密码备份宿主验收](backup/%E9%AA%8C%E6%94%B6.md) · `backup/验收.md`
- [安装监督进程未收到启动确认](cold-process-io/%E9%AA%8C%E6%94%B6.md) · `cold-process-io/验收.md`
- [冷部署第 6 步的 Stream closed](cold-stream/%E7%BB%93%E8%AE%BA.md) · `cold-stream/结论.md`
- [build377 二轮签名成品的对应源码](final2/source-rights/%E6%9D%A5%E6%BA%90%E9%AA%8C%E6%94%B6.md) · `final2/source-rights/来源验收.md`
- [build376 直接覆盖升级至最终 build377](final2/upgrade/%E9%AA%8C%E6%94%B6%E8%AE%B0%E5%BD%95.md) · `final2/upgrade/验收记录.md`
- [build377 最终签名包体积验收](final3/size/%E4%BD%93%E7%A7%AF%E9%AA%8C%E6%94%B6.md) · `final3/size/体积验收.md`
- [build377 第三轮签包的 PRoot 与 talloc 对应源码](final3/source-rights/%E6%9D%A5%E6%BA%90%E9%AA%8C%E6%94%B6.md) · `final3/source-rights/来源验收.md`
- [正式 376 直接覆盖至第三份签名 377](final3/upgrade/%E9%AA%8C%E6%94%B6%E8%AE%B0%E5%BD%95.md) · `final3/upgrade/验收记录.md`
- [build377 本地交付包的局域网与 Android 系统验收](final4/lan/%E9%AA%8C%E6%94%B6.md) · `final4/lan/验收.md`
- [build377 正式签名包体积验收](final4/size/%E4%BD%93%E7%A7%AF%E9%AA%8C%E6%94%B6.md) · `final4/size/体积验收.md`
- [build377 第四轮签包的 PRoot 与 talloc 对应源码](final4/source-rights/%E6%9D%A5%E6%BA%90%E9%AA%8C%E6%94%B6.md) · `final4/source-rights/来源验收.md`
- [正式 376 直接覆盖至第四份最终签名 377](final4/upgrade/%E9%AA%8C%E6%94%B6%E8%AE%B0%E5%BD%95.md) · `final4/upgrade/验收记录.md`
- [build377 局域网 HTTPS 与模拟器验收](lan/%E5%B1%80%E5%9F%9F%E7%BD%91%E4%B8%93%E9%A1%B9%E9%AA%8C%E6%94%B6.md) · `lan/局域网专项验收.md`
- [Android 原生标识](native-identity/%E5%8E%9F%E7%94%9F%E6%A0%87%E8%AF%86%E9%AA%8C%E6%94%B6.md) · `native-identity/原生标识验收.md`
- [旧包与候选 PRoot 在 Android 11 ARM64 上能否运行](proot-emulator/%E5%85%BC%E5%AE%B9%E5%AE%9E%E6%B5%8B.md) · `proot-emulator/兼容实测.md`
- [另存为、作品文件和 Download/DSHA 验收](saf/%E9%AA%8C%E6%94%B6%E8%AE%B0%E5%BD%95.md) · `saf/验收记录.md`
- [build377 屏幕归属和授权回传专项](screen/%E7%AC%AC%E4%B8%80%E8%BD%AE%E8%AE%B0%E5%BD%95%EF%BC%88%E5%90%8E%E7%BB%AD%E5%B7%B2%E6%9B%BF%E6%8D%A2%E6%95%B4%E5%B1%8F%E5%AE%9E%E7%8E%B0%EF%BC%89.md) · `screen/第一轮记录（后续已替换整屏实现）.md`
- [build377 屏幕读取验收](screen/%E9%AA%8C%E6%94%B6%E8%AE%B0%E5%BD%95.md) · `screen/验收记录.md`
- [原生界面语言检查](settings/i18n-check/%E5%A4%8D%E6%A0%B8.md) · `settings/i18n-check/复核.md`
- [build377 R4 设置与实时通道](settings/r4/R4%E8%AE%BE%E7%BD%AE%E5%A4%8D%E9%AA%8C.md) · `settings/r4/R4设置复验.md`
- [build377 设置保存与网页清单验收](settings/%E8%AE%BE%E7%BD%AE%E4%B8%93%E9%A1%B9%E9%AA%8C%E6%94%B6.md) · `settings/设置专项验收.md`
- [Build377 Shizuku 服务与 Binder 验收](shizuku/%E9%AA%8C%E6%94%B6.md) · `shizuku/验收.md`
- [build377 离线安装包体积](size/%E4%BD%93%E7%A7%AF%E9%AA%8C%E6%94%B6.md) · `size/体积验收.md`
- [Standard PRoot、talloc 与 proroot 来源验收](source-rights/%E6%9D%A5%E6%BA%90%E9%AA%8C%E6%94%B6.md) · `source-rights/来源验收.md`
- [WebKit 局域网实时连接验收](web-identity/lan-mux/%E9%AA%8C%E6%94%B6.md) · `web-identity/lan-mux/验收.md`
- [build377 网页和桌面快捷方式品牌验收](web-identity/%E9%AA%8C%E6%94%B6.md) · `web-identity/验收.md`
- [在 Android 系统里真正点过什么](%E6%88%90%E5%93%81%E7%B3%BB%E7%BB%9F%E5%AE%9E%E6%B5%8B.md) · `成品系统实测.md`
- [DSHA build377 本地软件验收](%E6%9C%AC%E5%9C%B0%E9%AA%8C%E6%94%B6.md) · `本地验收.md`
- [DSHA build377 第三次签包检查](%E7%AC%AC%E4%B8%89%E6%AC%A1%E7%AD%BE%E5%8C%85%E6%A3%80%E6%9F%A5.md) · `第三次签包检查.md`
- [DSHA build377 第二次签包检查](%E7%AC%AC%E4%BA%8C%E6%AC%A1%E7%AD%BE%E5%8C%85%E6%A3%80%E6%9F%A5.md) · `第二次签包检查.md`
- [build377 遗留范围与实测索引](%E9%81%97%E7%95%99%E8%8C%83%E5%9B%B4.md) · `遗留范围.md`
- [DSHA build377 第一次签包的检查记录](%E9%A6%96%E6%AC%A1%E7%AD%BE%E5%8C%85%E6%A3%80%E6%9F%A5.md) · `首次签包检查.md`
