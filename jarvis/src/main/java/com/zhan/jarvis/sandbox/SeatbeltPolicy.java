package com.zhan.jarvis.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS Seatbelt 策略生成器。
 *
 * <p>策略采用默认拒绝模型：命令可以读取宿主文件系统，但只能写入当前工作区和可选的临时目录；
 * 网络默认关闭。工作区通过 sandbox-exec 的参数传入，避免把用户路径直接拼接进 SBPL。</p>
 */
final class SeatbeltPolicy {

    static final String SEATBELT_EXECUTABLE = "/usr/bin/sandbox-exec";
    static final String MODE_WORKSPACE_WRITE = "workspace-write";
    static final String MODE_READ_ONLY = "read-only";

    private static final String BASE_POLICY = """
            (version 1)
            (deny default)

            ; 允许启动子进程，子进程自动继承当前 Seatbelt 策略。
            (allow process-exec)
            (allow process-fork)
            (allow signal (target same-sandbox))
            (allow process-info* (target same-sandbox))

            ; workspace-write 与 Codex 的本地模式一致：全盘可读，写权限单独收窄。
            (allow file-read*)
            (allow file-test-existence)
            (allow file-map-executable)

            ; 命令行程序运行所需的基础系统能力。
            (allow sysctl-read)
            (allow sysctl-write (sysctl-name "kern.grade_cputype"))
            (allow mach-lookup)
            (allow user-preference-read)
            (allow ipc-posix*)
            (allow iokit-open (iokit-registry-entry-class "RootDomainUserClient"))
            (allow pseudo-tty)
            (allow file-ioctl)
            (allow system-fsctl)

            ; 允许标准输入输出、终端和系统日志套接字。
            (allow file-write*
              (literal "/dev/null")
              (literal "/dev/zero")
              (literal "/dev/tty")
              (literal "/dev/ptmx")
              (regex #"^/dev/fd/[0-9]+$")
              (regex #"^/dev/ttys[0-9]+$"))
            (allow network-outbound (literal "/private/var/run/syslog"))
            """;

    private SeatbeltPolicy() {
    }

    static String normalizeMode(String mode) {
        String normalized = mode == null ? MODE_WORKSPACE_WRITE : mode.trim().toLowerCase();
        if (MODE_WORKSPACE_WRITE.equals(normalized) || MODE_READ_ONLY.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的 OS sandbox mode: " + mode
                + "，可选值为 workspace-write、read-only");
    }

    static String build(String mode, boolean networkAccess, boolean allowTempWrite) {
        String normalizedMode = normalizeMode(mode);
        var policy = new StringBuilder(BASE_POLICY);

        if (MODE_WORKSPACE_WRITE.equals(normalizedMode)) {
            policy.append("""

                    ; 仅允许修改本次运行绑定的工作区。
                    (allow file-write* (subpath (param "WORKSPACE")))
                    ; 禁止删除工作区根目录本身，避免破坏后续运行使用的权限锚点。
                    (deny file-write-unlink
                      (require-all
                        (literal (param "WORKSPACE"))
                        (vnode-type DIRECTORY)))
                    """);
        }

        if (allowTempWrite) {
            policy.append("""

                    ; 编译器、包管理器和测试工具通常需要临时目录。
                    (allow file-write* (subpath (param "TEMP_DIR")))
                    """);
        }

        if (networkAccess) {
            policy.append("""

                    ; 网络权限必须显式开启。
                    (allow network-outbound)
                    (allow network-inbound)
                    (allow network-bind)
                    (allow system-socket)
                    """);
        }
        return policy.toString();
    }

    static List<String> command(String policy, Path workspace, Path tempDir, String shellCommand) {
        var command = new ArrayList<String>();
        command.add(SEATBELT_EXECUTABLE);
        command.add("-p");
        command.add(policy);
        command.add("-DWORKSPACE=" + workspace);
        command.add("-DTEMP_DIR=" + tempDir);
        command.add("--");
        command.add("/bin/sh");
        command.add("-c");
        command.add(shellCommand);
        return List.copyOf(command);
    }
}
