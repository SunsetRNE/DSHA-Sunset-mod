package com.deepseekharness.app;

interface IShellService {
    String exec(String cmd) = 0;
    // Native-only typed launcher; generic shell text may not invoke app_process.
    String execVirtualScreen(String command) = 1;
    // Historical DSHA synchronous transaction (wire 16777114).
    void destroy() = 16777113;
    // Shizuku sends wire 16777115 with FLAG_ONEWAY when removing a UserService.
    oneway void destroyFromManager() = 16777114;
}
