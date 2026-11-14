# PTY 出生握手的 Linux host fixture

入口：[test-pty-birth-handshake.py](../../test-pty-birth-handshake.py)。它使用指定 JDK 的真实 JNI 头文件，直接编译当前 [dsha-pty.c](../../termux-jni/dsha-pty.c)、[头文件](../../termux-jni/dsha-pty.h)和[本夹具](pty-birth-host.c)，不复制一份生产握手算法，不下载/修改工具链、不改受管 ELF。

```sh
python3 tools/test-pty-birth-handshake.py --jdk /path/to/jdk17 -v
```

需要真实 Linux `/proc`、fork、socketpair、setsid、prctl、host C 编译器与 Linux JDK 17 头文件；缺能力明确失败，不 skip、不改用 Android 或其他屏幕/通道。

边界与 oracle：

- 真实执行当前 C 的 child self-stat→等待 ACK→exec，以及 parent JNI callback→ACK/失败 kill+waitpid。固定 guest 只打印 `DSHA_PTY_HOST_GUEST`，在 ACK 前及 callback 中检查 marker 管道为空，接受后必须读到实际 exec 的打印。
- JNI function table 是显式 fake callback，只模拟 Java 接受/拒绝/异常/查找失败/分配失败的边界；不运行 NativeProcess Java，不证明 JVM 异常、Android loadLibrary 或非调试应用 `/proc` 权限。自读 stat 的 PID/PPID/PGID/SID/birth 由单独 host oracle 与当时 `/proc` 比较，该 oracle 不替代生产 Java 判据。
- 每个场景另 fork 一个无关兄弟进程，要求 owned child 已被 reap、兄弟仍存活；所有测试清理 PID 都是本夹具新 fork 且尚未 reap 的直接子进程。fixture 的 `PDEATHSIG` 仅用于驱动超时的测试 containment，不宣称生产 PTY 已具备这条能力。
- 成功、callback 拒绝/异常、class/method/string 失败、ACK 写失败走当前 parent C；错误 ACK、parent disconnect、parent 保持通道开放但不 ACK 走当前 child C。故意不发送 stat 的 child 验证当前 parent 的 3000ms 截止，两端超时回归的耗时下界收紧为 2800ms；child 以 fork 前起点计时，避免扣掉父端 ACK 前观察等待而误报，parent 以进入原生父握手前计时。此下界不宣称精确 3000ms 的计时精度或 ROM 时序。拒绝后不重放 subprocess/ACK。
- malformed 场景发送无末尾换行的 32 字节报文和 4096 字节报文，验证交付/截断前缀缺换行时不进入 Java 回调。此用例不证明任意带有效换行前缀的超长报文都会被原生层识别，也不把 private self-stat 通道描述成恶意同进程代码的隔离边界。

共 13 项行为测试；source/header/fixture/编译二进制摘要在测试前后核对，终态 `INPUTS_UNCHANGED` 只表示输入/二进制未变，行为成败以 unittest 的测试数和退出码为准。宿主 binary 的摘要用于绑定本次执行，不宣称跨编译器字节复现，更不替代 Android libtermux.so 的构建/16 KB 对齐/签名 APK 验收。

范围与实际回执见 [A4 实现记录](../../../docs/design/kotlin-rust-rearchitecture-20261112/a4-implementation-evidence-20261114.md)。
