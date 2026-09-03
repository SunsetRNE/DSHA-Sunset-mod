/*
 * Decompiled with CFR 0.152.
 */
package com.deepseekharness.app;

import com.deepseekharness.app.IShellService;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ShellService
extends IShellService.Stub {
    @Override
    public String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String oldPath = env.get("PATH");
            env.put("PATH", (oldPath == null || oldPath.isEmpty() ? "" : oldPath + ":") + "/system/bin:/system/xbin:/sbin:/vendor/bin");
            Process p = pb.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int MAX = 262144;
            try (InputStream in = p.getInputStream();){
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bos.size() >= 262144) continue;
                    int w = Math.min(n, 262144 - bos.size());
                    bos.write(buf, 0, w);
                }
            }
            if (!Compat.waitFor(p, 30000)) {
                Compat.destroy(p);
                return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=timeout] \u547d\u4ee4\u6267\u884c\u8d85\u65f6(30s)\u5df2\u5f3a\u6740";
            }
            int code = p.exitValue();
            return bos.toString(StandardCharsets.UTF_8.name()) + "\n[EXIT=" + code + "]";
        }
        catch (Throwable e) {
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
    }
}
