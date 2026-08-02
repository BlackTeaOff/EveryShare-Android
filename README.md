<p align="center">
  <img src="app/src/main/res/drawable/logo_0.png" width="180" alt="EveryShare Logo">
</p>
<h1 align="center">EveryShare - Android</h1>

<p align="center">
基于 IPv6 的 P2P 互传应用程序
</p>

<p align="center">
<img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white">
<img src="https://img.shields.io/badge/Java-17+-blue.svg">
<img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white">
<img src="https://img.shields.io/badge/License-MIT-green.svg">
</p>

> 注意: 本项目仍处于初期阶段, 请勿在工作环境下使用!
>
> 本项目大量使用 AI 辅助开发。

## 项目简介

EveryShare 是一个基于 IPv6 的 P2P 互传应用程序。

它的目标如名字一样

- **Share EveryWhere**
- **Share EveryThing**
- **Share EveryWay**

### 1. EveryWhere

无论何时何地, 只要有两部终端(~~至少都有公网 IPv6~~)就可以互相Share.

目前仅在作者的环境测试过，在家使用 `移动5G/移动宽带/广电5G` 互相传可以跑满当前网络环境允许的上传/下载带宽。

不确定别的省份，跨省或别的运营商可不可以使用。

### 2. EveryThing

不局限于传输文件

未来希望它能实现**近距离安卓音频共享**:

想和熟人一起收听手机里的音频？

或是随便找一个附近的陌生人一起听歌？聊天？

它**也许**能解决这个问题, 敬请期待!

~~（实际上有人已经解决了，这个是几个月之前的构想）~~

### 3. EveryWay

市面上的这些互传软件选设备太无聊？

如果加上**NFC一碰连**的话, 会不会更有趣呢？(~~虽然好像已经有这样的技术了。~~)

## 架构设计

### 项目结构

- core: 普通的 Java socket

### 通信协议

- UDP: 打通 IPv6 UDP 防火墙，为打通 IPv6 TCP 防火墙做准备
- TCP: 打通 IPv6 TCP 防火墙 (TCP Simultaneous Open)，传输数据

## 界面架构
- Material 3

## Getting Started

### QQQQQuickStart

1. 前往[Release](https://github.com/BlackTeaOff/EveryShare/releases/tag/v0.1.0-alpha)里下载最新的`EveryShare_0.1.0-alpha`。
2. 安装运行即可。

### SSSSSlowStart

1. 确保已安装JDK 17+。

2. 克隆项目到本地

   ```git clone https://github.com/BlackTeaOff/EveryShare-Android.git```

3. 使用 Android Studio 打开项目, 等待 Gradle 下载依赖 。

4. 插上你的手机，打开 USB 调试，点击 IDE 右上角的运行（~~播放~~）按钮。

5. 开始体验EveryShare-Android最最最最最DEMO的版本吧！(~~别抱太高期望哦~~)

### TODO

- [x] 实现远程 IPv6 传输
- [ ] 找人来测试一下能不能用！
- [ ] NFC一碰传
- [ ] 文件夹传输
- [ ] Android音频共享
- [ ] 共享更多！(远程桌面, 串流等等)
- [ ] 未完待续...

## Contribution

看到这里的人, 我要! 感谢! 你!!!

如果你对本项目感兴趣, 欢迎任何形式的贡献~

- 看完README并在心里给作者默默加油~
- 遇到BUG, 请提Issue
- 有新点子, 也可以提Issue
- Fork/PR, ~~没用过~~ ~(不过可以试试)
- 或给个Star, 这是对我最最直接的支持!

> 写在最后:
>
> 也许这是我第一次自己开始做一个规模比较大, 完整的项目吧。(~~有点紧张~~)
>
> 不知道它的结局会是怎样......
>
> 虽然但是, 无论如何, 继续加油吧~
>
> 在看的人, 你也一样！
>
> BlackTea - 2026/4/29
