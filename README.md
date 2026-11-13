# Clash Meta for Android · UI 复刻版

这是我个人 fork 的 **UI 复刻版**：基于 [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid)（上游 v2.11.36），把主页界面按自己的想法重新做了一遍，内核和核心功能与上游完全一致，只改外观和布局，自用为主。

## 这个软件是什么

Clash Meta for Android 是 Android 平台的代理客户端，内核是 mihomo（Clash.Meta）：

- 支持 Shadowsocks、VMess、VLESS、Trojan、Hysteria 等主流协议
- 按规则智能分流：国内直连、国外走代理，也可以全局或直连模式
- 内置 GeoIP / GeoSite 规则数据库，支持订阅导入和节点测速、切换

## 本复刻版改了什么

- **全新主页**：深色薄荷绿玻璃拟态风格，顶部只保留一个猫图标
- **点阵世界地图**：按节点名称定位到对应国家首都，发光标记显示当前节点位置
- **横向胶囊控制栏**：替代原来的圆形开关，一键启停
- **状态 / 节点 / 流量+模式卡片**：连接时长、实时速度、当前节点与延迟一目了然
- **四个底部导航**：首页 / 统计 / 代理+配置（合并页） / 设置；统计页可看本次连接已用流量
- 所有数据均为真实读取，不做假数据；包名与官方版本不同，**可与官方版共存安装**

## 下载

到 [Releases](https://github.com/h1son5633/ClashMetaForAndroid/releases) 下载最新 APK（arm64-v8a，适用于大多数安卓手机）。

## 说明

- 本仓库是个人学习与自用 fork，遵循上游的 GPL-3.0 许可证
- 上游项目：https://github.com/MetaCubeX/ClashMetaForAndroid ，内核：https://github.com/MetaCubeX/mihomo
